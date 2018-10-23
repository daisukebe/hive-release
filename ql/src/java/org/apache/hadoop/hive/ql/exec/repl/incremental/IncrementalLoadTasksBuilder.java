/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.exec.repl.incremental;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.ql.DriverContext;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.exec.TaskFactory;
import org.apache.hadoop.hive.ql.exec.repl.ReplLoadWork;
import org.apache.hadoop.hive.ql.exec.repl.ReplStateLogWork;
import org.apache.hadoop.hive.ql.exec.repl.util.AddDependencyToLeaves;
import org.apache.hadoop.hive.ql.exec.repl.util.TaskTracker;
import org.apache.hadoop.hive.ql.exec.util.DAGTraversal;
import org.apache.hadoop.hive.ql.hooks.ReadEntity;
import org.apache.hadoop.hive.ql.hooks.WriteEntity;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.ql.parse.ReplicationSpec;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.parse.repl.DumpType;
import org.apache.hadoop.hive.ql.parse.repl.ReplLogger;
import org.apache.hadoop.hive.ql.parse.repl.load.DumpMetaData;
import org.apache.hadoop.hive.ql.parse.repl.load.UpdatedMetaDataTracker;
import org.apache.hadoop.hive.ql.parse.repl.load.log.IncrementalLoadLogger;
import org.apache.hadoop.hive.ql.parse.repl.load.message.MessageHandler;
import org.apache.hadoop.hive.ql.plan.AlterDatabaseDesc;
import org.apache.hadoop.hive.ql.plan.AlterTableDesc;
import org.apache.hadoop.hive.ql.plan.DDLWork;
import org.apache.hadoop.hive.ql.plan.DependencyCollectionWork;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;

public class IncrementalLoadTasksBuilder {
  private final String dbName, tableName;
  private final IncrementalLoadEventsIterator iterator;
  private final HashSet<ReadEntity> inputs;
  private final HashSet<WriteEntity> outputs;
  private Log log;
  private final HiveConf conf;
  private final ReplLogger replLogger;
  public static long numIteration;
  private final Long eventTo;

  public IncrementalLoadTasksBuilder(String dbName, String tableName, String loadPath,
                                     IncrementalLoadEventsIterator iterator, HiveConf conf, Long eventTo) {
    this.dbName = dbName;
    this.tableName = tableName;
    this.iterator = iterator;
    inputs = new HashSet<>();
    outputs = new HashSet<>();
    this.conf = conf;
    log = null;
    numIteration = 0;
    replLogger = new IncrementalLoadLogger(dbName, loadPath, iterator.getNumEvents());
    replLogger.startLog();
    this.eventTo = eventTo;
  }

  public Task<? extends Serializable> execute(DriverContext driverContext, Hive hive, Log log,
                                              ReplLoadWork loadWork) throws Exception {
    Task<? extends Serializable> evTaskRoot = TaskFactory.get(new DependencyCollectionWork(), conf, true);
    Task<? extends Serializable> taskChainTail = evTaskRoot;
    Long lastReplayedEvent = null;
    this.log = log;
    numIteration++;

    log.debug("Iteration num " + numIteration);
    TaskTracker tracker = new TaskTracker(conf.getIntVar(HiveConf.ConfVars.REPL_APPROX_MAX_LOAD_TASKS));

    while (iterator.hasNext() && tracker.canAddMoreTasks()) {
      FileStatus dir = iterator.next();
      String location = dir.getPath().toUri().toString();


      // event loads will behave similar to table loads, with one crucial difference
      // precursor order is strict, and each event must be processed after the previous one.
      // The way we handle this strict order is as follows:
      // First, we start with a taskChainTail which is a dummy noop task (a DependecyCollectionTask)
      // at the head of our event chain. For each event we process, we tell analyzeTableLoad to
      // create tasks that use the taskChainTail as a dependency. Then, we collect all those tasks
      // and introduce a new barrier task(also a DependencyCollectionTask) which depends on all
      // these tasks. Then, this barrier task becomes our new taskChainTail. Thus, we get a set of
      // tasks as follows:
      //
      //                 --->ev1.task1--                          --->ev2.task1--
      //                /               \                        /               \
      //  evTaskRoot-->*---->ev1.task2---*--> ev1.barrierTask-->*---->ev2.task2---*->evTaskChainTail
      //                \               /
      //                 --->ev1.task3--
      //
      // Once this entire chain is generated, we add evTaskRoot to rootTasks, so as to execute the
      // entire chain

      DumpMetaData eventDmd = new DumpMetaData(new Path(location), conf);

      if (!shouldReplayEvent(dir, eventDmd.getDumpType(), dbName, tableName)) {
        log.debug("skipping event from " + location + " to " + dbName + "." + tableName +
                "num task " + tracker.numberOfTasks());
        continue;
      }

      log.debug("Loading event from " + location + " to " + dbName + "." + tableName +
              "num task " + tracker.numberOfTasks());

      MessageHandler.Context context = new MessageHandler.Context(dbName,
              tableName, location, taskChainTail,
              eventDmd, conf, hive, driverContext.getCtx(), this.log);
      List<Task<? extends Serializable>> evTasks = analyzeEventLoad(context);

      if ((evTasks != null) && (!evTasks.isEmpty())){
        ReplStateLogWork replStateLogWork = new ReplStateLogWork(replLogger,
                dir.getPath().getName(),
                eventDmd.getDumpType().toString());
        Task<? extends Serializable> barrierTask = TaskFactory.get(replStateLogWork, conf, true);
        AddDependencyToLeaves function = new AddDependencyToLeaves(barrierTask);
        DAGTraversal.traverse(evTasks, function);
        log.debug("Updated taskChainTail from " + taskChainTail.getClass() + taskChainTail.getId()
                + " to " + barrierTask.getClass() + barrierTask.getId());
        tracker.addTaskList(taskChainTail.getChildTasks());
        taskChainTail = barrierTask;
      }
      lastReplayedEvent = eventDmd.getEventTo();
    }

    if (iterator.hasNext()) {
      // add load task to start the next iteration
      taskChainTail.addDependentTask(TaskFactory.get(loadWork, conf, true));
    } else {
      // if no events were replayed, then add a task to update the last repl id of the database/table to last event id.
      if (taskChainTail == evTaskRoot) {
        String lastEventid = eventTo.toString();
        if (StringUtils.isEmpty(tableName)) {
          taskChainTail = dbUpdateReplStateTask(dbName, lastEventid, taskChainTail);
          this.log.debug("no events to replay, set last repl id of db  " + dbName + " to " + lastEventid);
        } else {
          taskChainTail = tableUpdateReplStateTask(dbName, tableName, null, lastEventid, taskChainTail);
          this.log.debug("no events to replay, set last repl id of table " + dbName + "." + tableName + " to " +
                  lastEventid);
        }
      }
      Map<String, String> dbProps = new HashMap<>();
      dbProps.put(ReplicationSpec.KEY.CURR_STATE_ID.toString(), String.valueOf(lastReplayedEvent));
      ReplStateLogWork replStateLogWork = new ReplStateLogWork(replLogger, dbProps);
      Task<? extends Serializable> barrierTask = TaskFactory.get(replStateLogWork, conf, true);
      taskChainTail.addDependentTask(barrierTask);
      log.debug("Added " + taskChainTail.getClass() + ":" + taskChainTail.getId()
              + " as a precursor of barrier task "
              + barrierTask.getClass() + ":" + barrierTask.getId());
    }
    log.info("Iteration " + numIteration + " done with num task : " +
            tracker.numberOfTasks() + ", lastReplayedEvent : " + lastReplayedEvent);
    return evTaskRoot;
  }

  private boolean isEventNotReplayed(Map<String, String> params, FileStatus dir, DumpType dumpType) {
    if (params != null && (params.containsKey(ReplicationSpec.KEY.CURR_STATE_ID.toString()))) {
      String replLastId = params.get(ReplicationSpec.KEY.CURR_STATE_ID.toString());
      if (Long.parseLong(replLastId) >= Long.parseLong(dir.getPath().getName())) {
        log.debug("Event " + dumpType + " with replId " + Long.parseLong(dir.getPath().getName())
                + " is already replayed. LastReplId - " +  Long.parseLong(replLastId));
        return false;
      }
    }
    return true;
  }

  private boolean shouldReplayEvent(FileStatus dir, DumpType dumpType, String dbName, String tableName) {
    // if database itself is null then we can not filter out anything.
    if (dbName == null || dbName.isEmpty()) {
      return true;
    } else if ((tableName == null) || (tableName.isEmpty())) {
      Database database;
      try {
        database = Hive.get().getDatabase(dbName);
        return database == null ? true : isEventNotReplayed(database.getParameters(), dir, dumpType);
      } catch (HiveException e) {
        //may be the db is getting created in this load
        log.debug("failed to get the database " + dbName);
        return true;
      }
    } else {
      Table tbl;
      try {
        tbl = Hive.get().getTable(dbName, tableName);
        return isEventNotReplayed(tbl.getParameters(), dir, dumpType);
      } catch (HiveException e) {
        // may be the table is getting created in this load
        log.debug("failed to get the table " + dbName + "." + tableName);
        return true;
      }
    }
  }

  private List<Task<? extends Serializable>> analyzeEventLoad(
          MessageHandler.Context context)
          throws SemanticException {
    MessageHandler messageHandler = context.dmd.getDumpType().handler();
    List<Task<? extends Serializable>> tasks = messageHandler.handle(context);

    if (context.precursor != null) {
      for (Task<? extends Serializable> t : tasks) {
        context.precursor.addDependentTask(t);
        log.debug("Added " + context.precursor.getClass() + ":" + context.precursor.getId() + " as a precursor of "
                + t.getClass() + ":" + t.getId());
      }
    }

    inputs.addAll(messageHandler.readEntities());
    outputs.addAll(messageHandler.writeEntities());
    return addUpdateReplStateTasks(StringUtils.isEmpty(context.tableName),
            messageHandler.getUpdatedMetadata(), tasks);
  }

  private Task<? extends Serializable> tableUpdateReplStateTask(
          String dbName,
          String tableName,
          Map<String, String> partSpec,
          String replState,
          Task<? extends Serializable> preCursor) {
    HashMap<String, String> mapProp = new HashMap<>();
    mapProp.put(ReplicationSpec.KEY.CURR_STATE_ID.toString(), replState);

    AlterTableDesc alterTblDesc =  new AlterTableDesc(
            AlterTableDesc.AlterTableTypes.ADDPROPS, new ReplicationSpec(replState, replState));
    alterTblDesc.setProps(mapProp);
    alterTblDesc.setOldName(dbName + "." + tableName);
    alterTblDesc.setPartSpec((HashMap<String, String>)partSpec);

    Task<? extends Serializable> updateReplIdTask = TaskFactory.get(
            new DDLWork(inputs, outputs, alterTblDesc), conf, true);

    // Link the update repl state task with dependency collection task
    if (preCursor != null) {
      preCursor.addDependentTask(updateReplIdTask);
      log.debug("Added " + preCursor.getClass() + ":" + preCursor.getId() + " as a precursor of "
              + updateReplIdTask.getClass() + ":" + updateReplIdTask.getId());
    }
    return updateReplIdTask;
  }

  private Task<? extends Serializable> dbUpdateReplStateTask(
          String dbName,
          String replState,
          Task<? extends Serializable> preCursor) {
    HashMap<String, String> mapProp = new HashMap<>();
    mapProp.put(ReplicationSpec.KEY.CURR_STATE_ID.toString(), replState);

    AlterDatabaseDesc alterDbDesc = new AlterDatabaseDesc(
            dbName, mapProp, new ReplicationSpec(replState, replState));
    Task<? extends Serializable> updateReplIdTask = TaskFactory.get(
            new DDLWork(inputs, outputs, alterDbDesc), conf, true);

    // Link the update repl state task with dependency collection task
    if (preCursor != null) {
      preCursor.addDependentTask(updateReplIdTask);
      log.debug("Added " + preCursor.getClass() + ":" + preCursor.getId() + " as a precursor of "
              + updateReplIdTask.getClass() + ":" + updateReplIdTask.getId());
    }
    return updateReplIdTask;
  }

  private List<Task<? extends Serializable>> addUpdateReplStateTasks(
          boolean isDatabaseLoad,
          UpdatedMetaDataTracker updatedMetadata,
          List<Task<? extends Serializable>> importTasks) {
    String replState = updatedMetadata.getReplicationState();
    String dbName = updatedMetadata.getDatabase();
    String tableName = updatedMetadata.getTable();

    // If no import tasks generated by the event or no table updated for table level load, then no
    // need to update the repl state to any object.
    if (importTasks.isEmpty() || (!isDatabaseLoad && (tableName == null))) {
      log.debug("No objects need update of repl state: Either 0 import tasks or table level load");
      return importTasks;
    }

    // Create a barrier task for dependency collection of import tasks
    Task<? extends Serializable> barrierTask = TaskFactory.get(new DependencyCollectionWork(), conf, true);

    // Link import tasks to the barrier task which will in-turn linked with repl state update tasks
    for (Task<? extends Serializable> t : importTasks){
      t.addDependentTask(barrierTask);
      log.debug("Added " + t.getClass() + ":" + t.getId() + " as a precursor of barrier task "
              + barrierTask.getClass() + ":" + barrierTask.getId());
    }

    List<Task<? extends Serializable>> tasks = new ArrayList<>();
    Task<? extends Serializable> updateReplIdTask;

    // If any partition is updated, then update repl state in partition object
    for (final Map<String, String> partSpec : updatedMetadata.getPartitions()) {
      updateReplIdTask = tableUpdateReplStateTask(dbName, tableName, partSpec, replState, barrierTask);
      tasks.add(updateReplIdTask);
    }

    if (tableName != null) {
      // If any table/partition is updated, then update repl state in table object
      updateReplIdTask = tableUpdateReplStateTask(dbName, tableName, null, replState, barrierTask);
      tasks.add(updateReplIdTask);
    }

    // For table level load, need not update replication state for the database
    if (isDatabaseLoad) {
      // If any table/partition is updated, then update repl state in db object
      updateReplIdTask = dbUpdateReplStateTask(dbName, replState, barrierTask);
      tasks.add(updateReplIdTask);
    }

    // At least one task would have been added to update the repl state
    return tasks;
  }
}
