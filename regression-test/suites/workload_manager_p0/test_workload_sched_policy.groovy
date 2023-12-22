// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

suite("test_workload_sched_policy") {

    sql "set experimental_enable_nereids_planner = false;"

    sql "drop workload schedule policy if exists full_policy_policy;"
    sql "drop workload schedule policy if exists set_action_policy;"
    sql "drop workload schedule policy if exists move_action_policy;"
    sql "drop workload schedule policy if exists test_cancel_policy;"
    sql "drop workload schedule policy if exists test_set_var_policy;"
    sql "drop workload schedule policy if exists test_set_var_policy2;"

    // 1 create cancel policy
    sql "create workload schedule policy test_cancel_policy " +
            " conditions(query_time > 10) " +
            " actions(cancel_query) properties('enabled'='false'); "

    // 2 create cancel policy
    sql "create workload schedule policy move_action_policy " +
            "conditions(username='root') " +
            "actions(move_query_to_group 'normal');"

    // 3 create set policy
    sql "create workload schedule policy set_action_policy " +
            "conditions(query_time > 10, username='root') " +
            "actions(set_session_variable 'workload_group=normal');"

    // 4 create policy with property
    sql "create workload schedule policy full_policy_policy " +
            "conditions(query_time > 10, username='root') " +
            "actions(set_session_variable 'workload_group=normal') " +
            "properties( " +
            "'enabled' = 'false', " +
            "'priority'='10' " +
            ");"

    qt_select_policy_tvf "select name,condition,action,priority,enabled,version from workload_schedule_policy() order by name;"

    // test_alter
    sql "alter workload schedule policy full_policy_policy properties('priority'='2', 'enabled'='false');"

    // create failed check
    try {
        sql "create workload schedule policy failed_policy " +
                "conditions(abc > 123) actions(cancel_query);"
    } catch(Exception e) {
        assertTrue(e.getMessage().contains("invalid metric name"))
    }

    try {
        sql "create workload schedule policy failed_policy " +
                "conditions(query_time > 123) actions(abc);"
    } catch(Exception e) {
        assertTrue(e.getMessage().contains("invalid action type"))
    }

    try {
        sql "alter workload schedule policy full_policy_policy properties('priority'='abc');"
    } catch (Exception e) {
        assertTrue(e.getMessage().contains("invalid priority property value"))
    }

    try {
        sql "alter workload schedule policy full_policy_policy properties('enabled'='abc');"
    } catch (Exception e) {
        assertTrue(e.getMessage().contains("invalid enabled property value"))
    }

    try {
        sql "alter workload schedule policy full_policy_policy properties('priority'='10000');"
    } catch (Exception e) {
        assertTrue(e.getMessage().contains("priority can only between"))
    }

    try {
        sql "create workload schedule policy conflict_policy " +
                "conditions (username = 'root')" +
                "actions(cancel_query, move_query_to_group 'normal');"
    } catch (Exception e) {
        assertTrue(e.getMessage().contains("can not exist in one policy at same time"))
    }

    try {
        sql "create workload schedule policy conflict_policy " +
                "conditions (username = 'root') " +
                "actions(cancel_query, cancel_query);"
    } catch (Exception e) {
        assertTrue(e.getMessage().contains("duplicate action in one policy"))
    }

    try {
        sql "create workload schedule policy conflict_policy " +
                "conditions (username = 'root') " +
                "actions(set_session_variable 'workload_group=normal', set_session_variable 'workload_group=abc');"
    } catch (Exception e) {
        assertTrue(e.getMessage().contains("duplicate set_session_variable action args one policy"))
    }

    // drop
    sql "drop workload schedule policy full_policy_policy;"
    sql "drop workload schedule policy set_action_policy;"
    sql "drop workload schedule policy move_action_policy;"
    sql "drop workload schedule policy test_cancel_policy;"

    qt_select_policy_tvf_after_drop "select name,condition,action,priority,enabled,version from workload_schedule_policy() order by name;"

    // test workload schedule policy
    sql "ADMIN SET FRONTEND CONFIG ('workload_sched_policy_interval_ms' = '500');"
    sql """drop user if exists test_workload_sched_user"""
    sql """create user test_workload_sched_user identified by '12345'"""
    sql """grant ADMIN_PRIV on *.*.* to test_workload_sched_user"""

    // 1 create test_set_var_policy
    sql "create workload schedule policy test_set_var_policy conditions(username='test_workload_sched_user')" +
            "actions(set_session_variable 'parallel_pipeline_task_num=33');"
    def result1 = connect(user = 'test_workload_sched_user', password = '12345', url = context.config.jdbcUrl) {
        logger.info("begin sleep 15s to wait")
        Thread.sleep(15000)
        sql "show variables like '%parallel_pipeline_task_num%';"
    }
    assertEquals("parallel_pipeline_task_num", result1[0][0])
    assertEquals("33", result1[0][1])

    // 2 create test_set_var_policy2 with higher priority
    sql "create workload schedule policy test_set_var_policy2 conditions(username='test_workload_sched_user') " +
            "actions(set_session_variable 'parallel_pipeline_task_num=22') properties('priority'='10');"
    def result2 = connect(user = 'test_workload_sched_user', password = '12345', url = context.config.jdbcUrl) {
        Thread.sleep(3000)
        sql "show variables like '%parallel_pipeline_task_num%';"
    }
    assertEquals("parallel_pipeline_task_num", result2[0][0])
    assertEquals("22", result2[0][1])

    // 3 disable test_set_var_policy2
    sql "alter workload schedule policy test_set_var_policy2 properties('enabled'='false');"
    def result3 = connect(user = 'test_workload_sched_user', password = '12345', url = context.config.jdbcUrl) {
        Thread.sleep(3000)
        sql "show variables like '%parallel_pipeline_task_num%';"
    }
    assertEquals("parallel_pipeline_task_num", result3[0][0])
    assertEquals("33", result3[0][1])

    sql "ADMIN SET FRONTEND CONFIG ('workload_sched_policy_interval_ms' = '10000');"

    sql "drop workload schedule policy if exists full_policy_policy;"
    sql "drop workload schedule policy if exists set_action_policy;"
    sql "drop workload schedule policy if exists move_action_policy;"
    sql "drop workload schedule policy if exists test_cancel_policy;"
    sql "drop workload schedule policy if exists test_set_var_policy;"
    sql "drop workload schedule policy if exists test_set_var_policy2;"

}