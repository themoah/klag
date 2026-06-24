package io.github.themoah.klag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Env}.
 *
 * <p>Env vars can't be set in-process, so these exercise the system-property fallbacks
 * ({@code -DNAME} and dotted {@code -Dname.dotted}) and precedence. The env-var path stays
 * highest in {@link Env#resolve} and is unchanged from before.
 */
class EnvTest {

  private static final String NAME = "KLAG_TEST_PORT";
  private static final String DOTTED = "klag.test.port";

  @AfterEach
  void clearProps() {
    System.clearProperty(NAME);
    System.clearProperty(DOTTED);
  }

  @Test
  void exactPropertyResolves() {
    System.setProperty(NAME, "8881");
    assertEquals(8881, Env.getInt(NAME, 8888));
  }

  @Test
  void dottedPropertyResolves() {
    System.setProperty(DOTTED, "8881");
    assertEquals(8881, Env.getInt(NAME, 8888));
  }

  @Test
  void exactPropertyWinsOverDotted() {
    System.setProperty(NAME, "1111");
    System.setProperty(DOTTED, "2222");
    assertEquals(1111, Env.getInt(NAME, 8888));
  }

  @Test
  void absentUsesDefault() {
    assertEquals(8888, Env.getInt(NAME, 8888));
  }

  @Test
  void invalidUsesDefault() {
    System.setProperty(NAME, "not-a-number");
    assertEquals(8888, Env.getInt(NAME, 8888));
  }

  @Test
  void blankUsesDefault() {
    System.setProperty(NAME, "   ");
    assertEquals(8888, Env.getInt(NAME, 8888));
  }

  @Test
  void boolFromProperty() {
    System.setProperty(DOTTED, "true");
    assertTrue(Env.getBool(NAME, false));
  }
}
