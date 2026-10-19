package com.whyql.whyql.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.whyql.whyql.datasource.domain.SchemaSnapshot;
import com.whyql.whyql.schema.parse.JavaEntityParser;
import com.whyql.whyql.schema.parse.ParsedSchema;

/**
 * Plain unit test: JPA entities and Spring @Document classes become tables without
 * regexes over Java source. Non-entity classes are ignored.
 */
class JavaEntityParserTest {

  private final JavaEntityParser parser = new JavaEntityParser();

  private static final String CUSTOMER = """
      package com.example.shop;
      import jakarta.persistence.*;
      @Entity
      @Table(name = "customers")
      public class Customer {
        @Id
        @GeneratedValue
        private Long id;
        @Column(name = "email", nullable = false)
        private String email;
        private String name;
        @Transient
        private String displayLabel;
        public static final String KIND = "customer";
      }
      """;

  private static final String ORDER = """
      package com.example.shop;
      import jakarta.persistence.*;
      import java.math.BigDecimal;
      import java.time.LocalDateTime;
      @Entity
      public class Order {
        @Id
        private Long id;
        @ManyToOne
        @JoinColumn(name = "customer_id", referencedColumnName = "id")
        private Customer customer;
        @Column(nullable = false)
        private BigDecimal total;
        private LocalDateTime placedAt;
      }
      """;

  private static final String PRODUCT_DOC = """
      package com.example.shop;
      import org.springframework.data.annotation.Id;
      import org.springframework.data.mongodb.core.mapping.Document;
      @Document(collection = "products")
      public class Product {
        @Id
        private String id;
        private String sku;
        private double price;
      }
      """;

  private static final String NOT_AN_ENTITY = """
      package com.example.shop;
      public class PriceCalculator {
        public double withTax(double net) { return net * 1.2; }
      }
      """;

  @Test
  void entitiesBecomeTablesWithKeysAndRelations() {
    ParsedSchema schema = new ParsedSchema();
    parser.parseSource(CUSTOMER, schema);
    parser.parseSource(ORDER, schema);
    parser.parseSource(PRODUCT_DOC, schema);
    parser.parseSource(NOT_AN_ENTITY, schema);

    SchemaSnapshot snapshot = schema.toSnapshot("shop");
    assertThat(snapshot.tables()).extracting(SchemaSnapshot.SchemaTable::name)
        .containsExactlyInAnyOrder("customers", "order", "products");

    SchemaSnapshot.SchemaTable customers = table(snapshot, "customers");
    assertThat(customers.columns()).extracting(SchemaSnapshot.SchemaColumn::name)
        .containsExactly("id", "email", "name");
    assertThat(customers.primaryKeys()).containsExactly("id");
    assertThat(column(customers, "email").nullable()).isFalse();

    SchemaSnapshot.SchemaTable order = table(snapshot, "order");
    assertThat(order.columns()).extracting(SchemaSnapshot.SchemaColumn::name)
        .contains("id", "customer_id", "total", "placedAt");
    assertThat(column(order, "total").type()).isEqualTo("NUMERIC");
    assertThat(order.foreignKeys()).hasSize(1);
    assertThat(order.foreignKeys().get(0).columns()).containsExactly("customer_id");
    assertThat(order.foreignKeys().get(0).referencedTable()).isEqualTo("customer");

    SchemaSnapshot.SchemaTable products = table(snapshot, "products");
    assertThat(products.primaryKeys()).containsExactly("id");
    assertThat(column(products, "price").type()).isEqualTo("DOUBLE PRECISION");
  }

  private static SchemaSnapshot.SchemaTable table(SchemaSnapshot snapshot, String name) {
    return snapshot.tables().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
  }

  private static SchemaSnapshot.SchemaColumn column(SchemaSnapshot.SchemaTable table, String name) {
    return table.columns().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
  }
}
