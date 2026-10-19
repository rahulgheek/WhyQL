/**
 * Board: presentation boards of widgets (manual table pick, validated SQL, or
 * a natural-language prompt), each with a snapshotted result. Cross-module
 * surface: {@code services} (BoardService, WidgetSpec) and {@code domain}
 * (Board, BoardWidget entities, mapped by the api layer). Repositories,
 * execution internals are internal to the module.
 */
@ApplicationModule(displayName = "Board",
    allowedDependencies = { "datasource::domain", "datasource::services", "query" })
package com.whyql.whyql.board;

import org.springframework.modulith.ApplicationModule;
