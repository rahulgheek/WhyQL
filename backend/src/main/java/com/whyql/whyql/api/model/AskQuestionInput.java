package com.whyql.whyql.api.model;

import java.util.UUID;

/** GraphQL {@code AskQuestionInput}: which data source, and the question. */
public record AskQuestionInput(UUID dataSourceId, String question) {
}
