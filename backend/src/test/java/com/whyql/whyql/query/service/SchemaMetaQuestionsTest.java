package com.whyql.whyql.query.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.whyql.whyql.query.service.SchemaMetaQuestions.Kind;

/** Pure matcher tests — no Spring context, no containers. */
class SchemaMetaQuestionsTest {

  @Test
  void detectsTableCountQuestions() {
    assertThat(SchemaMetaQuestions.classify("how many tables are there"))
        .isEqualTo(Optional.of(Kind.TABLE_COUNT));
    assertThat(SchemaMetaQuestions.classify("How many tables do I have?"))
        .isEqualTo(Optional.of(Kind.TABLE_COUNT));
    assertThat(SchemaMetaQuestions.classify("number of tables"))
        .isEqualTo(Optional.of(Kind.TABLE_COUNT));
    assertThat(SchemaMetaQuestions.classify("how many tables are in the database"))
        .isEqualTo(Optional.of(Kind.TABLE_COUNT));
  }

  @Test
  void detectsTableListQuestions() {
    assertThat(SchemaMetaQuestions.classify("what tables are there"))
        .isEqualTo(Optional.of(Kind.TABLE_LIST));
    assertThat(SchemaMetaQuestions.classify("list all tables"))
        .isEqualTo(Optional.of(Kind.TABLE_LIST));
    assertThat(SchemaMetaQuestions.classify("show me the tables"))
        .isEqualTo(Optional.of(Kind.TABLE_LIST));
    assertThat(SchemaMetaQuestions.classify("which tables do I have"))
        .isEqualTo(Optional.of(Kind.TABLE_LIST));
    assertThat(SchemaMetaQuestions.classify("List Tables"))
        .isEqualTo(Optional.of(Kind.TABLE_LIST));
  }

  @Test
  void ignoresDataQuestionsThatMentionTables() {
    assertThat(SchemaMetaQuestions.classify("top 10 products by revenue from the orders table"))
        .isEmpty();
    assertThat(SchemaMetaQuestions.classify("how many orders were placed last month"))
        .isEmpty();
    assertThat(SchemaMetaQuestions.classify("sum of sales by product category"))
        .isEmpty();
    assertThat(SchemaMetaQuestions.classify("show me monthly revenue for the last 12 months"))
        .isEmpty();
  }

  @Test
  void ignoresEverythingElse() {
    assertThat(SchemaMetaQuestions.classify(null)).isEmpty();
    assertThat(SchemaMetaQuestions.classify("")).isEmpty();
    assertThat(SchemaMetaQuestions.classify("   ")).isEmpty();
    assertThat(SchemaMetaQuestions.classify("show me revenue")).isEmpty();
    assertThat(SchemaMetaQuestions.classify("count of users from the users table between january and march "
        + "grouped by city with totals and averages included and sorted descending additionally"))
        .isEmpty(); // too long to be a meta question
  }

  @Test
  void detectsSchemaListQuestions() {
    assertThat(SchemaMetaQuestions.classify("name all schemas in the database"))
        .isEqualTo(Optional.of(Kind.SCHEMA_LIST));
    assertThat(SchemaMetaQuestions.classify("list the schemas"))
        .isEqualTo(Optional.of(Kind.SCHEMA_LIST));
    assertThat(SchemaMetaQuestions.classify("what schemas are there"))
        .isEqualTo(Optional.of(Kind.SCHEMA_LIST));
    assertThat(SchemaMetaQuestions.classify("show me the schemas"))
        .isEqualTo(Optional.of(Kind.SCHEMA_LIST));
  }

  @Test
  void detectsSchemaCountQuestions() {
    assertThat(SchemaMetaQuestions.classify("how many schemas are there"))
        .isEqualTo(Optional.of(Kind.SCHEMA_COUNT));
    assertThat(SchemaMetaQuestions.classify("number of schemas in the database"))
        .isEqualTo(Optional.of(Kind.SCHEMA_COUNT));
  }

  @Test
  void tableQuestionsWithTheWordSchemaStayTableQuestions() {
    assertThat(SchemaMetaQuestions.classify("how many tables in the schema"))
        .isEqualTo(Optional.of(Kind.TABLE_COUNT));
    assertThat(SchemaMetaQuestions.classify("list the tables in my schema"))
        .isEqualTo(Optional.of(Kind.TABLE_LIST));
  }
}
