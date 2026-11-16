/*
 * Copyright (c) 2026, Alibaba Group Holding Limited
 * See the LICENSE file in the project root for more information.
 */

package com.aliyun.polardb2.jdbc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for {@link TypeInfoCache#parseQualifiedTypeName(String)}.
 */
public class TypeInfoCacheParseTest {

  @Test
  public void testSinglePartNames() {
    assertArrayEquals(new String[]{"int4"}, TypeInfoCache.parseQualifiedTypeName("int4"));
    assertArrayEquals(new String[]{"int4"}, TypeInfoCache.parseQualifiedTypeName("INT4"));
    assertArrayEquals(
        new String[]{"CorrectCasing"},
        TypeInfoCache.parseQualifiedTypeName("\"CorrectCasing\""));
    assertArrayEquals(
        new String[]{"Evil.Table"},
        TypeInfoCache.parseQualifiedTypeName("\"Evil.Table\""));
  }

  @Test
  public void testTwoPartNames() {
    assertArrayEquals(
        new String[]{"pkg_crr", "tbl_crr_callback_result"},
        TypeInfoCache.parseQualifiedTypeName("PKG_CRR.TBL_CRR_CALLBACK_RESULT"));
    assertArrayEquals(
        new String[]{"Composites", "ComplexCompositeTest"},
        TypeInfoCache.parseQualifiedTypeName("\"Composites\".\"ComplexCompositeTest\""));
  }

  @Test
  public void testThreePartPackageNames() {
    assertArrayEquals(
        new String[]{"cas", "pkg_crr", "tbl_crr_callback_result"},
        TypeInfoCache.parseQualifiedTypeName("CAS.PKG_CRR.TBL_CRR_CALLBACK_RESULT"));
    assertArrayEquals(
        new String[]{"CAS", "PKG_CRR", "TBL_CRR_CALLBACK_RESULT"},
        TypeInfoCache.parseQualifiedTypeName("\"CAS\".\"PKG_CRR\".\"TBL_CRR_CALLBACK_RESULT\""));
    assertArrayEquals(
        new String[]{"cas", "MyPkg", "tbl_crr"},
        TypeInfoCache.parseQualifiedTypeName("CAS.\"MyPkg\".TBL_CRR"));
    assertArrayEquals(
        new String[]{"a\"b", "pkg", "typ"},
        TypeInfoCache.parseQualifiedTypeName("\"a\"\"b\".pkg.typ"));
  }

  @Test
  public void testMalformedAndOverQualifiedNamesReturnNull() {
    assertNull(TypeInfoCache.parseQualifiedTypeName(""));
    assertNull(TypeInfoCache.parseQualifiedTypeName(".a"));
    assertNull(TypeInfoCache.parseQualifiedTypeName("a."));
    assertNull(TypeInfoCache.parseQualifiedTypeName("a..b"));
    assertNull(TypeInfoCache.parseQualifiedTypeName("a.b.c.d"));
    assertNull(TypeInfoCache.parseQualifiedTypeName("\"unclosed"));
    assertNull(TypeInfoCache.parseQualifiedTypeName("\"a\"extra.b"));
    assertNull(TypeInfoCache.parseQualifiedTypeName("a\"b\".c"));
  }
}
