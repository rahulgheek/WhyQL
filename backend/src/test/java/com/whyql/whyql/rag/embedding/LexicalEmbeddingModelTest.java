package com.whyql.whyql.rag.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingRequest;

class LexicalEmbeddingModelTest {

  private final LexicalEmbeddingModel model = new LexicalEmbeddingModel();

  @Test
  void dimensionsMatchConfiguredVectorStore() {
    assertThat(model.dimensions()).isEqualTo(LexicalEmbeddingModel.DIMENSIONS);
    assertThat(model.embed("")).hasSize(LexicalEmbeddingModel.DIMENSIONS);
  }

  @Test
  void embeddingsAreDeterministic() {
    float[] first = model.embed("Table orders columns amount total paid");
    float[] second = model.embed("Table orders columns amount total paid");
    assertThat(second).containsExactly(first);
  }

  @Test
  void embeddingsAreUnitNormalizedAcrossDistinctTexts() {
    for (String text : List.of("orders revenue monthly", "users emails signups", "")) {
      float[] v = model.embed(text);
      double norm = 0;
      for (float c : v) {
        norm += c * c;
      }
      if (text.isEmpty()) {
        assertThat(norm).isEqualTo(0.0); // no usable signal -> zero vector
      } else {
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
      }
    }
  }

  @Test
  void distinctTextsProduceDistinctVectors() {
    float[] orders = model.embed("table orders columns amount paid");
    float[] users = model.embed("table users columns email signup date");
    assertThat(users).isNotEqualTo(orders);
  }

  @Test
  void relatedQuestionScoresHigherThanUnrelated() {
    float[] question = model.embed(
        "How much revenue did paid orders bring in this month?");
    float[] ordersDoc = model.embed(
        "Table orders columns amount status total; primary keys id");
    float[] usersDoc = model.embed(
        "Table users columns email signup_date name; primary keys id");

    double related = cosine(question, ordersDoc);
    double unrelated = cosine(question, usersDoc);
    assertThat(related).isGreaterThan(unrelated);
  }

  @Test
  void callEmbedsBatchWithSequentialIndices() {
    List<String> texts = List.of("alpha table", "beta column", "gamma rows");
    var response = model.call(new EmbeddingRequest(texts, null));

    assertThat(response.getResults()).hasSize(3);
    for (int i = 0; i < 3; i++) {
      assertThat(response.getResults().get(i).getIndex()).isEqualTo(i);
      assertThat(response.getResults().get(i).getOutput())
          .containsExactly(model.embed(texts.get(i)));
    }
  }

  @Test
  void embedDelegatesToTextEmbeddingForDocuments() {
    Document doc = new Document("table orders columns amount paid");
    assertThat(model.embed(doc)).containsExactly(model.embed("table orders columns amount paid"));
  }

  private static double cosine(float[] a, float[] b) {
    double dot = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
    }
    return dot;
  }
}
