/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.store.hive;

import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category.LIST;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category.MAP;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category.PRIMITIVE;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category.STRUCT;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.arrow.vector.complex.MapVector;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.UnionMode;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.ArrowType.Binary;
import org.apache.arrow.vector.types.pojo.ArrowType.Bool;
import org.apache.arrow.vector.types.pojo.ArrowType.Date;
import org.apache.arrow.vector.types.pojo.ArrowType.Decimal;
import org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint;
import org.apache.arrow.vector.types.pojo.ArrowType.Int;
import org.apache.arrow.vector.types.pojo.ArrowType.Timestamp;
import org.apache.arrow.vector.types.pojo.ArrowType.Utf8;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.ql.io.orc.OrcInputFormat;
import org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category;
import org.apache.hadoop.hive.serde2.typeinfo.DecimalTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.ListTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.MapTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.PrimitiveTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.StructTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;
import org.apache.hadoop.hive.serde2.typeinfo.UnionTypeInfo;
import org.apache.hadoop.mapred.InputFormat;

import com.dremio.exec.catalog.ColumnNestedTooDeepException;
import com.google.common.collect.Sets;

public class HiveSchemaConverter {

  private static Set<Category> ORC_SUPPORTED_TYPES = Sets.newHashSet(LIST, STRUCT, PRIMITIVE);
  private static Set<Category> PARQUET_SUPPORTED_TYPES = Sets.newHashSet(LIST, STRUCT, PRIMITIVE, MAP);
  private static boolean isTypeNotSupported(InputFormat<?,?> format, Category category, boolean includeParquetComplexTypes, boolean isMapTypeEnabled) {
    // No restrictions on primitive types
    if (category.equals(PRIMITIVE)) {
      return false;
    }

    // Don't support map anywhere.
    if (category.equals(MAP)) {
      return !isMapTypeEnabled;
    }

    // All complex types supported in Orc
    if (format instanceof OrcInputFormat && ORC_SUPPORTED_TYPES.contains(category)) {
      return false;
    }

    // Support only list and struct in Parquet along with primitive types. // MapRedParquetInputFormat, VectorizedParquetInputformat
    if (includeParquetComplexTypes && MapredParquetInputFormat.class.isAssignableFrom(format.getClass()) && PARQUET_SUPPORTED_TYPES.contains(category)) {
      return false;
    }

    return true;
  }

  private static boolean supportsDroppingSubFields(InputFormat<?,?> format) {
    if (MapredParquetInputFormat.class.isAssignableFrom(format.getClass())) {
      return true;
    }
    return false;
  }

  public static Field getArrowFieldFromHiveType(String name, TypeInfo typeInfo, InputFormat<?, ?> format, boolean includeParquetComplexTypes, boolean isMapTypeEnabled) {
    if (isTypeNotSupported(format, typeInfo.getCategory(), includeParquetComplexTypes, isMapTypeEnabled)) {
      return null;
    }

    switch (typeInfo.getCategory()) {
      case PRIMITIVE:
        return HiveSchemaConverter.getArrowFieldFromHivePrimitiveType(name, typeInfo, true);
      case LIST: {
        ListTypeInfo lti = (ListTypeInfo) typeInfo;
        TypeInfo elementTypeInfo = lti.getListElementTypeInfo();
        Field inner = HiveSchemaConverter.getArrowFieldFromHiveType("$data$", elementTypeInfo, format, includeParquetComplexTypes, isMapTypeEnabled);
        if (inner == null) {
          return null;
        }
        return new Field(name, new FieldType(true, Types.MinorType.LIST.getType(), null),
          Collections.singletonList(inner));
      }
      case STRUCT: {
        StructTypeInfo sti = (StructTypeInfo) typeInfo;
        ArrayList<String> fieldNames = sti.getAllStructFieldNames();
        ArrayList<Field> structFields = new ArrayList<Field>();
        for (String fieldName : fieldNames) {
          TypeInfo fieldTypeInfo = sti.getStructFieldTypeInfo(fieldName);
          Field f = HiveSchemaConverter.getArrowFieldFromHiveType(fieldName,
            fieldTypeInfo, format, includeParquetComplexTypes, isMapTypeEnabled);
          if (f == null) {
            if (supportsDroppingSubFields(format)) {
              continue;
            } else {
              return null;
            }
          }
          structFields.add(f);
        }
        if (structFields.isEmpty()) {
          return null;
        }
        return new Field(name, new FieldType(true, Types.MinorType.STRUCT.getType(), null),
          structFields);
      }
      case UNION: {
        UnionTypeInfo uti = (UnionTypeInfo) typeInfo;
        ArrayList<Field> unionFields = new ArrayList<Field>();
        final List<TypeInfo> objectTypeInfos = uti.getAllUnionObjectTypeInfos();
        int[] typeIds = new int[objectTypeInfos.size()];
        for (int idx = 0; idx < objectTypeInfos.size(); ++idx) {
          TypeInfo fieldTypeInfo = objectTypeInfos.get(idx);
          Field fieldToGetArrowType = HiveSchemaConverter.getArrowFieldFromHiveType("", fieldTypeInfo, format, includeParquetComplexTypes, isMapTypeEnabled);
          if (fieldToGetArrowType == null) {
            return null;
          }
          ArrowType arrowType = fieldToGetArrowType.getType();
          if (arrowType == null) {
            return null;
          }
          // In a union, Arrow expects the field name for each member to be the same as the "minor type" name.
          Types.MinorType minorType = Types.getMinorTypeForArrowType(arrowType);
          Field f = HiveSchemaConverter.getArrowFieldFromHiveType(minorType.name().toLowerCase(), fieldTypeInfo, format, includeParquetComplexTypes, isMapTypeEnabled);
          if (f == null) {
            return null;
          }
          typeIds[idx] = Types.getMinorTypeForArrowType(f.getType()).ordinal();
          unionFields.add(f);
        }
        return new Field(name, FieldType.nullable(new ArrowType.Union(UnionMode.Sparse, typeIds)), unionFields);
      }
      case MAP: {
        MapTypeInfo mti = (MapTypeInfo) typeInfo;
        TypeInfo keyTypeInfo = mti.getMapKeyTypeInfo();
        Field keyField = HiveSchemaConverter.getArrowFieldFromHivePrimitiveType("key", keyTypeInfo, false);
        if (keyField == null || !(keyField.getType().getTypeID() == ArrowType.ArrowTypeID.Utf8)){
          return null;
        }
        TypeInfo valueTypeInfo = mti.getMapValueTypeInfo();
        Field valueField = HiveSchemaConverter.getArrowFieldFromHiveType("value", valueTypeInfo, format, includeParquetComplexTypes, isMapTypeEnabled);
        if (valueField == null || valueField.getType().isComplex()) {
          return null;
        }
        ArrayList<Field> structFields = new ArrayList<Field>();
        structFields.add(keyField);
        structFields.add(valueField);
        Field structMap = new Field(MapVector.DATA_VECTOR_NAME, new FieldType(false, Types.MinorType.STRUCT.getType(), null), structFields);
        return new Field(name, new FieldType(true, new ArrowType.Map(false), null), Collections.singletonList(structMap));
      }
      default:
        return null;
    }
  }

  public static Field getArrowFieldFromHivePrimitiveType(String name, TypeInfo typeInfo, boolean isNullable) {
    switch (typeInfo.getCategory()) {
    case PRIMITIVE:
      PrimitiveTypeInfo pTypeInfo = (PrimitiveTypeInfo) typeInfo;
      switch (pTypeInfo.getPrimitiveCategory()) {
      case BOOLEAN:

        return new Field(name, new FieldType(isNullable, new Bool(), null), null);
      case BYTE:
        return new Field(name, new FieldType(isNullable, new Int(32, true), null), null);
      case SHORT:
        return new Field(name, new FieldType(isNullable, new Int(32, true), null), null);

      case INT:
        return new Field(name, new FieldType(isNullable, new Int(32, true), null), null);

      case LONG:
        return new Field(name, new FieldType(isNullable, new Int(64, true), null), null);

      case FLOAT:
        return new Field(name, new FieldType(isNullable, new FloatingPoint(FloatingPointPrecision.SINGLE), null), null);

      case DOUBLE:
        return new Field(name, new FieldType(isNullable, new FloatingPoint(FloatingPointPrecision.DOUBLE), null), null);

      case DATE:
        return new Field(name, new FieldType(isNullable, new Date(DateUnit.MILLISECOND), null), null);

      case TIMESTAMP:
        return new Field(name, new FieldType(isNullable, new Timestamp(TimeUnit.MILLISECOND, null), null), null);

      case BINARY:
        return new Field(name, new FieldType(isNullable, new Binary(), null), null);
      case DECIMAL: {
        DecimalTypeInfo decimalTypeInfo = (DecimalTypeInfo) pTypeInfo;
        return new Field(name, new FieldType(isNullable, new Decimal(decimalTypeInfo.getPrecision(), decimalTypeInfo.getScale(), 128), null), null);
      }

      case STRING:
      case VARCHAR:
      case CHAR: {
        return new Field(name, new FieldType(isNullable, new Utf8(), null), null);
      }
      case UNKNOWN:
      case VOID:
      default:
        // fall through.
      }
    default:
    }

    return null;
  }

  /**
   * For primitive field depth is zero.
   * List adds one level to its child
   * Struct adds one level to its deepest child
   * Map adds one level to its value field
   * Union adds one level to its deepest chlld
   * @param typeInfo
   * @return
   */
  private static int findFieldDepth(final TypeInfo typeInfo) {
    if (typeInfo == null) {
      return 0;
    }

    switch (typeInfo.getCategory()) {
      case LIST: {
        ListTypeInfo lti = (ListTypeInfo) typeInfo;
        TypeInfo elementTypeInfo = lti.getListElementTypeInfo();
        return 1 + findFieldDepth(elementTypeInfo);
      }

      case STRUCT: {
        StructTypeInfo sti = (StructTypeInfo) typeInfo;
        ArrayList<String> fieldNames = sti.getAllStructFieldNames();
        ArrayList<Field> structFields = new ArrayList<Field>();
        int childDepth = 0;
        for (String fieldName : fieldNames) {
          TypeInfo fieldTypeInfo = sti.getStructFieldTypeInfo(fieldName);
          childDepth = Integer.max(childDepth, findFieldDepth(fieldTypeInfo));
        }
        return 1 + childDepth;
      }

      case UNION: {
        UnionTypeInfo uti = (UnionTypeInfo) typeInfo;
        final List<TypeInfo> objectTypeInfos = uti.getAllUnionObjectTypeInfos();
        int childDepth = 0;
        for (TypeInfo fieldTypeInfo : objectTypeInfos) {
          childDepth = Integer.max(childDepth, findFieldDepth(fieldTypeInfo));
        }
        return 1 + childDepth;
      }

      case MAP: {
        MapTypeInfo mti = (MapTypeInfo) typeInfo;
        final TypeInfo valueTypeInfo = mti.getMapValueTypeInfo();
        return 1 + findFieldDepth(valueTypeInfo);
      }

      case PRIMITIVE:
      default:
        return 0;
    }
  }

  /**
   * iterates over all fields of a table and checks if any field exceeds
   * maximum allowed nested level
   * @param table
   * @param maxNestedLevels
   */
  public static void checkFieldNestedLevels(final Table table, int maxNestedLevels, boolean isMapTypeEnabled) {
    for (FieldSchema hiveField : table.getSd().getCols()) {
      final TypeInfo typeInfo = TypeInfoUtils.getTypeInfoFromTypeString(hiveField.getType());
      if (typeInfo.getCategory().equals(MAP) && !isMapTypeEnabled) {
        continue;
      }
      int depth = findFieldDepth(typeInfo);
      if (depth > maxNestedLevels) {
        throw new ColumnNestedTooDeepException(hiveField.getName(), maxNestedLevels);
      }
    }
  }
}
