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


suite("test_cast_to_int_from_decimal64_10_5") {

    // This test case is generated from the correspoinding be UT test case,
    // update this case if the correspoinding be UT test case is updated,
    // e.g.: ../run-be-ut.sh --run --filter=FunctionCastToDecimalTest.* --gen_regression_case
    sql "drop table if exists test_cast_to_int_from_decimal64_10_5_0_nullable;"
    sql "create table test_cast_to_int_from_decimal64_10_5_0_nullable(f1 int, f2 decimalv3(10, 5)) properties('replication_num'='1');"
    sql """insert into test_cast_to_int_from_decimal64_10_5_0_nullable values (0, "0.00000"),(1, "0.00000"),(2, "0.00001"),(3, "-0.00001"),(4, "0.00009"),(5, "-0.00009"),(6, "0.99999"),(7, "-0.99999"),(8, "0.99998"),(9, "-0.99998"),(10, "0.09999"),(11, "-0.09999"),(12, "0.90000"),(13, "-0.90000"),(14, "0.90001"),(15, "-0.90001"),(16, "1.00000"),(17, "-1.00000"),(18, "1.00001"),(19, "-1.00001"),
      (20, "1.00009"),(21, "-1.00009"),(22, "1.99999"),(23, "-1.99999"),(24, "1.99998"),(25, "-1.99998"),(26, "1.09999"),(27, "-1.09999"),(28, "1.90000"),(29, "-1.90000"),(30, "1.90001"),(31, "-1.90001"),(32, "9.00000"),(33, "-9.00000"),(34, "9.00001"),(35, "-9.00001"),(36, "9.00009"),(37, "-9.00009"),(38, "9.99999"),(39, "-9.99999"),
      (40, "9.99998"),(41, "-9.99998"),(42, "9.09999"),(43, "-9.09999"),(44, "9.90000"),(45, "-9.90000"),(46, "9.90001"),(47, "-9.90001"),(48, "99999.00000"),(49, "-99999.00000"),(50, "99999.00001"),(51, "-99999.00001"),(52, "99999.00009"),(53, "-99999.00009"),(54, "99999.99999"),(55, "-99999.99999"),(56, "99999.99998"),(57, "-99999.99998"),(58, "99999.09999"),(59, "-99999.09999"),
      (60, "99999.90000"),(61, "-99999.90000"),(62, "99999.90001"),(63, "-99999.90001"),(64, "99998.00000"),(65, "-99998.00000"),(66, "99998.00001"),(67, "-99998.00001"),(68, "99998.00009"),(69, "-99998.00009"),(70, "99998.99999"),(71, "-99998.99999"),(72, "99998.99998"),(73, "-99998.99998"),(74, "99998.09999"),(75, "-99998.09999"),(76, "99998.90000"),(77, "-99998.90000"),(78, "99998.90001"),(79, "-99998.90001"),
      (80, "9999.00000"),(81, "-9999.00000"),(82, "9999.00001"),(83, "-9999.00001"),(84, "9999.00009"),(85, "-9999.00009"),(86, "9999.99999"),(87, "-9999.99999"),(88, "9999.99998"),(89, "-9999.99998"),(90, "9999.09999"),(91, "-9999.09999"),(92, "9999.90000"),(93, "-9999.90000"),(94, "9999.90001"),(95, "-9999.90001"),(96, "90000.00000"),(97, "-90000.00000"),(98, "90000.00001"),(99, "-90000.00001"),
      (100, "90000.00009"),(101, "-90000.00009"),(102, "90000.99999"),(103, "-90000.99999"),(104, "90000.99998"),(105, "-90000.99998"),(106, "90000.09999"),(107, "-90000.09999"),(108, "90000.90000"),(109, "-90000.90000"),(110, "90000.90001"),(111, "-90000.90001"),(112, "90001.00000"),(113, "-90001.00000"),(114, "90001.00001"),(115, "-90001.00001"),(116, "90001.00009"),(117, "-90001.00009"),(118, "90001.99999"),(119, "-90001.99999"),
      (120, "90001.99998"),(121, "-90001.99998"),(122, "90001.09999"),(123, "-90001.09999"),(124, "90001.90000"),(125, "-90001.90000"),(126, "90001.90001"),(127, "-90001.90001")
      ,(128, null);
    """

    sql "set enable_strict_cast=true;"
    qt_sql_0_strict 'select f1, cast(f2 as int) from test_cast_to_int_from_decimal64_10_5_0_nullable order by 1;'

    sql "set enable_strict_cast=false;"
    qt_sql_0_non_strict 'select f1, cast(f2 as int) from test_cast_to_int_from_decimal64_10_5_0_nullable order by 1;'

    sql "drop table if exists test_cast_to_int_from_decimal64_10_5_0_not_nullable;"
    sql "create table test_cast_to_int_from_decimal64_10_5_0_not_nullable(f1 int, f2 decimalv3(10, 5)) properties('replication_num'='1');"
    sql """insert into test_cast_to_int_from_decimal64_10_5_0_not_nullable values (0, "0.00000"),(1, "0.00000"),(2, "0.00001"),(3, "-0.00001"),(4, "0.00009"),(5, "-0.00009"),(6, "0.99999"),(7, "-0.99999"),(8, "0.99998"),(9, "-0.99998"),(10, "0.09999"),(11, "-0.09999"),(12, "0.90000"),(13, "-0.90000"),(14, "0.90001"),(15, "-0.90001"),(16, "1.00000"),(17, "-1.00000"),(18, "1.00001"),(19, "-1.00001"),
      (20, "1.00009"),(21, "-1.00009"),(22, "1.99999"),(23, "-1.99999"),(24, "1.99998"),(25, "-1.99998"),(26, "1.09999"),(27, "-1.09999"),(28, "1.90000"),(29, "-1.90000"),(30, "1.90001"),(31, "-1.90001"),(32, "9.00000"),(33, "-9.00000"),(34, "9.00001"),(35, "-9.00001"),(36, "9.00009"),(37, "-9.00009"),(38, "9.99999"),(39, "-9.99999"),
      (40, "9.99998"),(41, "-9.99998"),(42, "9.09999"),(43, "-9.09999"),(44, "9.90000"),(45, "-9.90000"),(46, "9.90001"),(47, "-9.90001"),(48, "99999.00000"),(49, "-99999.00000"),(50, "99999.00001"),(51, "-99999.00001"),(52, "99999.00009"),(53, "-99999.00009"),(54, "99999.99999"),(55, "-99999.99999"),(56, "99999.99998"),(57, "-99999.99998"),(58, "99999.09999"),(59, "-99999.09999"),
      (60, "99999.90000"),(61, "-99999.90000"),(62, "99999.90001"),(63, "-99999.90001"),(64, "99998.00000"),(65, "-99998.00000"),(66, "99998.00001"),(67, "-99998.00001"),(68, "99998.00009"),(69, "-99998.00009"),(70, "99998.99999"),(71, "-99998.99999"),(72, "99998.99998"),(73, "-99998.99998"),(74, "99998.09999"),(75, "-99998.09999"),(76, "99998.90000"),(77, "-99998.90000"),(78, "99998.90001"),(79, "-99998.90001"),
      (80, "9999.00000"),(81, "-9999.00000"),(82, "9999.00001"),(83, "-9999.00001"),(84, "9999.00009"),(85, "-9999.00009"),(86, "9999.99999"),(87, "-9999.99999"),(88, "9999.99998"),(89, "-9999.99998"),(90, "9999.09999"),(91, "-9999.09999"),(92, "9999.90000"),(93, "-9999.90000"),(94, "9999.90001"),(95, "-9999.90001"),(96, "90000.00000"),(97, "-90000.00000"),(98, "90000.00001"),(99, "-90000.00001"),
      (100, "90000.00009"),(101, "-90000.00009"),(102, "90000.99999"),(103, "-90000.99999"),(104, "90000.99998"),(105, "-90000.99998"),(106, "90000.09999"),(107, "-90000.09999"),(108, "90000.90000"),(109, "-90000.90000"),(110, "90000.90001"),(111, "-90000.90001"),(112, "90001.00000"),(113, "-90001.00000"),(114, "90001.00001"),(115, "-90001.00001"),(116, "90001.00009"),(117, "-90001.00009"),(118, "90001.99999"),(119, "-90001.99999"),
      (120, "90001.99998"),(121, "-90001.99998"),(122, "90001.09999"),(123, "-90001.09999"),(124, "90001.90000"),(125, "-90001.90000"),(126, "90001.90001"),(127, "-90001.90001");
    """

    sql "set enable_strict_cast=true;"
    qt_sql_0_strict 'select f1, cast(f2 as int) from test_cast_to_int_from_decimal64_10_5_0_not_nullable order by 1;'

    sql "set enable_strict_cast=false;"
    qt_sql_0_non_strict 'select f1, cast(f2 as int) from test_cast_to_int_from_decimal64_10_5_0_not_nullable order by 1;'

}