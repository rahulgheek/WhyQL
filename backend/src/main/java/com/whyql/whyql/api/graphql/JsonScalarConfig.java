package com.whyql.whyql.api.graphql;

import graphql.schema.Coercing;
import graphql.schema.GraphQLScalarType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

/**
 * Registers a hand-rolled {@code Json} object scalar (ExtendedScalars-like) so
 * result rows can carry numbers, strings and nulls without a scalar per cell.
 * No external extended-scalars dependency needed.
 */
@Configuration
public class JsonScalarConfig {

  /** Passes arbitrary JVM objects through to the JSON serializer. */
  static final class ObjectCoercing implements Coercing<Object, Object> {

    @Override
    public Object serialize(Object input) {
      return input;
    }

    @Override
    public Object parseValue(Object input) {
      return input;
    }

    @Override
    public Object parseLiteral(Object input) {
      // Variables with Json input aren't supported yet (result rows are
      // produced by the server only).
      throw new UnsupportedOperationException("Json scalar is output-only");
    }
  }

  @Bean
  RuntimeWiringConfigurer jsonScalarConfigurer() {
    return builder -> builder.scalar(GraphQLScalarType.newScalar()
        .name("Json")
        .description("Arbitrary JSON value (object, array, string, number, boolean or null)")
        .coercing(new ObjectCoercing())
        .build());
  }
}
