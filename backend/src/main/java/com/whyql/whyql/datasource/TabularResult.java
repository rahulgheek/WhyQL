package com.whyql.whyql.datasource;

import java.util.List;

/**
 * The only result shape analysis and visualization may see: column metadata plus
 * positional rows, with a truncation flag when the row cap cut the result short.
 * Values are plain JDBC objects (String/Number/Boolean/date-time); nested documents
 * are flattened to dot-path columns by the Mongo adapter in its phase.
 */
public record TabularResult(
    List<ResultColumn> columns,
    List<List<Object>> rows,
    boolean truncated) {

  public record ResultColumn(String name, String typeName) {}
}
