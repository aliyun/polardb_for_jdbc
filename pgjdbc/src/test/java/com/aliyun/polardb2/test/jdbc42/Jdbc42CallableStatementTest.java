/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.test.jdbc42;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.aliyun.polardb2.test.jdbc2.BaseTest4;

import org.junit.Test;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tests for JDBC 4.2 features in {@link com.aliyun.polardb2.jdbc.PgCallableStatement}.
 */
public class Jdbc42CallableStatementTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();

    try (Statement stmt = con.createStatement();) {
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getResultSetWithoutArg() "
                      + "RETURNS refcursor AS '  "
                      + "declare ref refcursor;"
                      + "begin OPEN ref FOR SELECT 1; RETURN ref; end; ' LANGUAGE plpgsql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getTs() RETURNS timestamp AS "
                      + "'SELECT ''2026-10-10 16:45:07''::timestamp' LANGUAGE sql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getDt() RETURNS date AS "
                      + "'SELECT ''2026-10-10''::date' LANGUAGE sql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getNum() RETURNS numeric AS "
                      + "'SELECT 42.5' LANGUAGE sql;");
      stmt.execute(
              "CREATE OR REPLACE FUNCTION testspg__getNullTs() RETURNS timestamp AS "
                      + "'SELECT NULL::timestamp' LANGUAGE sql;");
    }
  }

  final String func = "{ ? = call ";
  final String pkgName = "testspg__";

  @Override
  public void tearDown() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("drop FUNCTION testspg__getResultSetWithoutArg ();");
      stmt.execute("drop FUNCTION testspg__getTs ();");
      stmt.execute("drop FUNCTION testspg__getDt ();");
      stmt.execute("drop FUNCTION testspg__getNum ();");
      stmt.execute("drop FUNCTION testspg__getNullTs ();");
    }
    super.tearDown();
  }

  @Test
  public void testGetResultSetWithoutArg() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getResultSetWithoutArg () }")) {
      con.setAutoCommit(false); // ref cursors only work if auto commit is off
      call.registerOutParameter(1, Types.REF_CURSOR);
      call.execute();
      List<Integer> values = new ArrayList<>(1);
      try (ResultSet rs = call.getObject(1, ResultSet.class)) {
        while (rs.next()) {
          values.add(rs.getInt(1));
        }
      }
      assertEquals(Collections.singletonList(1), values);
    } finally {
      con.setAutoCommit(true);
    }
  }

  @Test
  public void testGetResultSetWithoutArgUnsupportedConversion() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getResultSetWithoutArg () }")) {
      con.setAutoCommit(false); // ref cursors only work if auto commit is off
      call.registerOutParameter(1, Types.REF_CURSOR);
      call.execute();
      try {
        // this should never be allowed even if more types will be implemented in the future
        call.getObject(1, ResultSetMetaData.class);
        fail("conversion from ResultSet to ResultSetMetaData should not be supported");
      } catch (SQLException e) {
        // should reach
      }
    } finally {
      con.setAutoCommit(true);
    }
  }

  @Test
  public void testRegisterOutParameter() throws SQLException {

    CallableStatement cs = null;

    cs = con.prepareCall("{ ? = call xxxx.yyyy (?,?,?,?)}");
    cs.registerOutParameter(1, Types.REF_CURSOR);

    cs.setLong(2, 1000L);
    cs.setLong(3, 500);
    cs.setLong(4, 3000);
    cs.setNull(5, Types.NUMERIC);
  }

  @Test
  public void testRegisterInoutParameter() throws SQLException {

    CallableStatement cs = null;

    cs = con.prepareCall("{call xxxx.yyyy (?)}");
    cs.setNull(1, Types.REF_CURSOR);
    cs.registerOutParameter(1, Types.REF_CURSOR);
  }

  /**
   * POLAR: getObject(int, Class) must convert a timestamp OUT parameter (registered as
   * Types.DATE, the Oracle DATE registration style) to java.time / textual targets.
   */
  @Test
  public void testGetObjectOfTimestampByClass() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getTs () }")) {
      call.registerOutParameter(1, Types.DATE);
      call.execute();
      assertEquals(LocalDate.of(2026, 10, 10), call.getObject(1, LocalDate.class));
      assertEquals(LocalDateTime.of(2026, 10, 10, 16, 45, 7),
          call.getObject(1, LocalDateTime.class));
      assertEquals(LocalDateTime.of(2026, 10, 10, 16, 45, 7).atOffset(ZoneOffset.UTC),
          call.getObject(1, java.time.OffsetDateTime.class));
      assertEquals(Timestamp.valueOf("2026-10-10 16:45:07"),
          call.getObject(1, Timestamp.class));
      assertEquals("2026-10-10 16:45:07.0", call.getObject(1, String.class));
    }
  }

  /** POLAR: getObject(int, Class) must convert a date OUT parameter to java.time targets. */
  @Test
  public void testGetObjectOfDateByClass() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getDt () }")) {
      call.registerOutParameter(1, Types.DATE);
      call.execute();
      assertEquals(LocalDate.of(2026, 10, 10), call.getObject(1, LocalDate.class));
      assertEquals(LocalDateTime.of(2026, 10, 10, 0, 0),
          call.getObject(1, LocalDateTime.class));
      assertEquals(java.sql.Date.valueOf("2026-10-10"),
          call.getObject(1, java.sql.Date.class));
    }
  }

  /** POLAR: getObject(int, Class) must convert a numeric OUT parameter to numeric targets. */
  @Test
  public void testGetObjectOfNumericByClass() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getNum () }")) {
      call.registerOutParameter(1, Types.NUMERIC);
      call.execute();
      assertEquals(new BigDecimal("42.5"), call.getObject(1, BigDecimal.class));
      assertEquals(Integer.valueOf(42), call.getObject(1, Integer.class));
      assertEquals(Long.valueOf(42L), call.getObject(1, Long.class));
      assertEquals(Double.valueOf(42.5d), call.getObject(1, Double.class));
      assertEquals("42.5", call.getObject(1, String.class));
    }
  }

  /** POLAR: getObject(int, Class) returns null for a SQL NULL OUT parameter. */
  @Test
  public void testGetObjectByClassNull() throws SQLException {
    assumeCallableStatementsSupported();
    try (CallableStatement call = con.prepareCall(func + pkgName + "getNullTs () }")) {
      call.registerOutParameter(1, Types.TIMESTAMP);
      call.execute();
      assertNull(call.getObject(1, LocalDate.class));
      assertNull(call.getObject(1, LocalDateTime.class));
      assertNull(call.getObject(1, String.class));
    }
  }
}
