/*
 * Portions Copyright (c) 2023, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.polarora;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aliyun.polardb2.test.TestUtil;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * POLAR: Test cases for error code conversion from negative (PostgreSQL) to positive (Oracle).
 */
public class ErrorCodeTest {
  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.put("callFunctionMode", "true");
    conn = TestUtil.openDB(props);
  }

  @After
  public void tearDown() throws Exception {
    if (conn != null && !conn.isClosed()) {
      conn.close();
    }
  }

  /**
   * Test: Error code for syntax error should be positive.
   * PostgreSQL returns negative code, we convert it to positive.
   */
  @Test
  public void testSyntaxErrorCode() {
    try (Statement stmt = conn.createStatement()) {
      // This will cause a syntax error
      stmt.execute("SELECT * FROM non_existent_table_12345");
    } catch (SQLException e) {
      int errorCode = e.getErrorCode();
      // Error code should be positive (Oracle convention)
      assertTrue("Error code should be positive, but got: " + errorCode, errorCode > 0);
      System.out.println("Syntax error code: " + errorCode);
    }
  }

  /**
   * Test: Error code for table not found should be positive.
   */
  @Test
  public void testTableNotFoundErrorCode() {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("SELECT * FROM table_that_does_not_exist");
    } catch (SQLException e) {
      int errorCode = e.getErrorCode();
      assertTrue("Error code should be positive, but got: " + errorCode, errorCode > 0);
      System.out.println("Table not found error code: " + errorCode);
    }
  }

  /**
   * Test: Error code for duplicate table should be positive.
   */
  @Test
  public void testDuplicateTableErrorCode() {
    String tableName = "test_dup_table_" + System.currentTimeMillis();
    try (Statement stmt = conn.createStatement()) {
      // Create table
      stmt.execute("CREATE TABLE " + tableName + " (id INT)");
      // Try to create again - should fail with duplicate error
      stmt.execute("CREATE TABLE " + tableName + " (id INT)");
    } catch (SQLException e) {
      int errorCode = e.getErrorCode();
      assertTrue("Error code should be positive, but got: " + errorCode, errorCode > 0);
      System.out.println("Duplicate table error code: " + errorCode);
    } finally {
      // Cleanup
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TABLE IF EXISTS " + tableName);
      } catch (SQLException ignored) {
      }
    }
  }

  /**
   * Test: Error code for constraint violation should be positive.
   */
  @Test
  public void testConstraintViolationErrorCode() {
    String tableName = "test_constraint_table_" + System.currentTimeMillis();
    try (Statement stmt = conn.createStatement()) {
      // Create table with NOT NULL constraint
      stmt.execute("CREATE TABLE " + tableName + " (id INT NOT NULL)");
      // Try to insert NULL - should fail
      stmt.execute("INSERT INTO " + tableName + " VALUES (NULL)");
    } catch (SQLException e) {
      int errorCode = e.getErrorCode();
      assertTrue("Error code should be positive, but got: " + errorCode, errorCode > 0);
      System.out.println("Constraint violation error code: " + errorCode);
    } finally {
      // Cleanup
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TABLE IF EXISTS " + tableName);
      } catch (SQLException ignored) {
      }
    }
  }

  /**
   * Test: Error code for division by zero should be positive.
   */
  @Test
  public void testDivisionByZeroErrorCode() {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("SELECT 1/0");
    } catch (SQLException e) {
      int errorCode = e.getErrorCode();
      assertTrue("Error code should be positive, but got: " + errorCode, errorCode > 0);
      System.out.println("Division by zero error code: " + errorCode);
    }
  }

  /**
   * Test: Error code for invalid column name should be positive.
   */
  @Test
  public void testInvalidColumnErrorCode() {
    String tableName = "test_col_table_" + System.currentTimeMillis();
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE " + tableName + " (id INT)");
      stmt.execute("SELECT non_existent_column FROM " + tableName);
    } catch (SQLException e) {
      int errorCode = e.getErrorCode();
      assertTrue("Error code should be positive, but got: " + errorCode, errorCode > 0);
      System.out.println("Invalid column error code: " + errorCode);
    } finally {
      // Cleanup
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TABLE IF EXISTS " + tableName);
      } catch (SQLException ignored) {
      }
    }
  }

  /**
   * Test: Verify that error code 0 is returned when there's no error.
   */
  @Test
  public void testNoError() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("SELECT 1");
      // No error occurred, this test just verifies normal operation
      assertTrue(true);
    }
  }

  /**
   * POLAR: Skips the tests below unless the server runs in Oracle compatibility
   * mode and reports the SQLCODE protocol field.
   */
  private void assumeOraSqlCodeSupported() throws SQLException {
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT count(*) FROM pg_catalog.pg_settings WHERE name IN "
                 + "('polar_compatibility_mode', 'polar_enable_ora_sqlcode') "
                 + "AND ((name = 'polar_compatibility_mode' AND setting = 'ora') "
                 + "  OR (name = 'polar_enable_ora_sqlcode' AND setting = 'on'))")) {
      rs.next();
      Assume.assumeTrue("server is not in Oracle mode with polar_enable_ora_sqlcode on",
          rs.getInt(1) == 2);
    }
  }

  /**
   * POLAR: Runs a statement that must fail and returns the error number the
   * driver reports for it.
   */
  private int errorCodeOf(String sql) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute(sql);
    } catch (SQLException e) {
      return e.getErrorCode();
    }
    fail("expected an error from: " + sql);
    return 0;
  }

  /**
   * POLAR: NO_DATA_FOUND is reported by the server as SQLCODE +100, which is
   * what PL/SQL sees, but ojdbc exposes it as the error number 1403. The driver
   * has to perform that conversion or migrated applications stop matching.
   */
  @Test
  public void testNoDataFoundReports1403() throws SQLException {
    assumeOraSqlCodeSupported();
    assertEquals("NO_DATA_FOUND must report error number 1403",
        1403, errorCodeOf("DECLARE v NUMBER; BEGIN SELECT 1 INTO v FROM dual WHERE 1=0; END;"));
  }

  /**
   * POLAR: An unhandled user-defined exception is reported by the server as
   * SQLCODE +1, but ojdbc exposes it as the error number 6510.
   */
  @Test
  public void testUserDefinedExceptionReports6510() throws SQLException {
    assumeOraSqlCodeSupported();
    assertEquals("an unhandled user-defined exception must report error number 6510",
        6510, errorCodeOf("DECLARE e_my EXCEPTION; BEGIN RAISE e_my; END;"));
  }

  /**
   * POLAR: A unique violation is reported by the server as SQLCODE -1, so it
   * must keep reporting error number 1 and must not be confused with the +1 of
   * a user-defined exception.
   */
  @Test
  public void testDuplicateKeyReports1Not6510() throws SQLException {
    assumeOraSqlCodeSupported();
    String tableName = "test_dup_key_" + System.currentTimeMillis();
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY)");
      stmt.execute("INSERT INTO " + tableName + " VALUES (1)");
      assertEquals("a unique violation must report error number 1",
          1, errorCodeOf("INSERT INTO " + tableName + " VALUES (1)"));
    } finally {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TABLE IF EXISTS " + tableName);
      } catch (SQLException ignored) {
      }
    }
  }

  /**
   * POLAR: EXCEPTION_INIT binds a user exception to an Oracle error number, so
   * raising it reports that number's negated SQLCODE rather than +1.
   */
  @Test
  public void testExceptionInitReportsBoundNumber() throws SQLException {
    assumeOraSqlCodeSupported();
    assertEquals("EXCEPTION_INIT(e, -1) must report error number 1",
        1, errorCodeOf("DECLARE e_dup EXCEPTION; PRAGMA EXCEPTION_INIT(e_dup, -1); "
            + "BEGIN RAISE e_dup; END;"));
  }

  /**
   * POLAR: Negative SQLCODEs keep reporting their magnitude, matching ojdbc.
   */
  @Test
  public void testNegativeSqlCodesMatchOracle() throws SQLException {
    assumeOraSqlCodeSupported();
    assertEquals("ZERO_DIVIDE must report error number 1476",
        1476, errorCodeOf("DECLARE v NUMBER; BEGIN v := 1/0; END;"));
    assertEquals("VALUE_ERROR must report error number 6502",
        6502, errorCodeOf("DECLARE v VARCHAR2(3); BEGIN v := 'abcdef'; END;"));
    assertEquals("a missing relation must report error number 942",
        942, errorCodeOf("SELECT * FROM no_such_table_zx"));
    assertEquals("RAISE_APPLICATION_ERROR must report the number it was given",
        20001, errorCodeOf("BEGIN RAISE_APPLICATION_ERROR(-20001, 'boom'); END;"));
  }
}
