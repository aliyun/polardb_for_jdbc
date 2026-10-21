/*
 * Portions Copyright (c) 2026, Alibaba Group Holding Limited
 */

package com.aliyun.polardb2.test.util;

import static org.junit.Assert.assertEquals;

import com.aliyun.polardb2.util.ServerErrorMessage;

import org.junit.Test;

/**
 * POLAR: Tests the PL/SQL SQLCODE to ORA-nnnnn error number conversion in
 * {@link ServerErrorMessage#getErrorCode()}.
 *
 * <p>The server sends the 'E' field using the PL/SQL SQLCODE convention, which
 * ojdbc does not expose directly: NO_DATA_FOUND is +100 on the wire but
 * SQLException.getErrorCode() must report 1403, and an unhandled user-defined
 * exception is +1 on the wire but must report 6510. Every other SQLCODE is
 * negative and its error number is the magnitude.
 */
public class ServerErrorMessageOraSqlCodeTest {

  /** Builds the NUL-delimited field blob the driver decodes from ErrorResponse. */
  private static String wire(String sqlState, String oraSqlCode) {
    StringBuilder sb = new StringBuilder();
    field(sb, 'S', "ERROR");
    field(sb, 'V', "ERROR");
    field(sb, 'C', sqlState);
    if (oraSqlCode != null) {
      field(sb, 'E', oraSqlCode);
    }
    field(sb, 'M', "message from server");
    sb.append('\0');
    return sb.toString();
  }

  private static void field(StringBuilder sb, char type, String value) {
    sb.append(type).append(value).append('\0');
  }

  private static int errorCode(String sqlState, String oraSqlCode) {
    return new ServerErrorMessage(wire(sqlState, oraSqlCode)).getErrorCode();
  }

  @Test
  public void noDataFoundReports1403() {
    assertEquals("NO_DATA_FOUND carries SQLCODE +100 but error number 1403",
        1403, errorCode("P0002", "100"));
  }

  @Test
  public void userDefinedExceptionReports6510() {
    assertEquals("an unhandled user-defined exception carries SQLCODE +1 but error number 6510",
        6510, errorCode("OR001", "1"));
  }

  @Test
  public void duplicateKeyReports1Not6510() {
    assertEquals("DUP_VAL_ON_INDEX carries SQLCODE -1, which must not be confused with +1",
        1, errorCode("23505", "-1"));
  }

  @Test
  public void negativeSqlCodesReportTheirMagnitude() {
    assertEquals(1476, errorCode("22012", "-1476"));
    assertEquals(6502, errorCode("22001", "-6502"));
    assertEquals(942, errorCode("42P01", "-942"));
    assertEquals(20001, errorCode("OR002", "-20001"));
    assertEquals(99999, errorCode("P0001", "-99999"));
  }

  @Test
  public void zeroReportsZero() {
    assertEquals(0, errorCode("00000", "0"));
  }

  @Test
  public void missingFieldReportsZero() {
    assertEquals("a server without polar_enable_ora_sqlcode sends no 'E' field",
        0, errorCode("22012", null));
  }

  @Test
  public void unparsableFieldReportsZero() {
    assertEquals(0, errorCode("22012", "not-a-number"));
    assertEquals(0, errorCode("22012", ""));
  }

  @Test
  public void sqlStateIsPassedThroughUntouched() {
    ServerErrorMessage err = new ServerErrorMessage(wire("OR005", "100"));
    assertEquals("OR005", err.getSQLState());
    assertEquals(1403, err.getErrorCode());
  }
}
