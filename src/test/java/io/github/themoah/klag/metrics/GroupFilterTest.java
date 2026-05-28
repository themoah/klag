package io.github.themoah.klag.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Broad coverage for {@link GroupFilter}: glob compilation, include disjunction,
 * exclude semantics, combined scenarios, and edge cases.
 */
class GroupFilterTest {

  @Nested
  class MatchAllDefaults {

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "*", ",", ",,,", " , , "})
    void blankOrStarOnlyInclude_matchesAll(String include) {
      GroupFilter f = new GroupFilter(include, "");
      assertTrue(f.matches("anything"));
      assertTrue(f.matches("foo-bar"));
      assertTrue(f.matches(""));
    }

    @Test
    void nullInclude_matchesAll() {
      GroupFilter f = new GroupFilter(null, null);
      assertTrue(f.matches("anything"));
      assertTrue(f.matches(""));
    }

    @Test
    void includeContainingStarSegment_collapsesToMatchAll() {
      GroupFilter f = new GroupFilter("ingest*,*", "");
      assertTrue(f.matches("anything"));
      assertTrue(f.matches("ingest-foo"));
      assertTrue(f.matches("enrich-foo"));
    }
  }

  @Nested
  class GlobSyntax {

    @ParameterizedTest
    @CsvSource({
      "'prod-*',   'prod-foo',  true",
      "'prod-*',   'prod-',     true",
      "'prod-*',   'dev-foo',   false",
      "'*-prod',   'foo-prod',  true",
      "'*-prod',   'foo-dev',   false",
      "'prod-?',   'prod-a',    true",
      "'prod-?',   'prod-ab',   false",
      "'prod-?',   'prod-',     false",
      "'a.b',      'a.b',       true",
      "'a.b',      'axb',       false",
      "'foo',      'foo',       true",
      "'foo',      'foobar',    false",
      "'foo',      'barfoo',    false"
    })
    void includeGlobBehavior(String include, String group, boolean expected) {
      GroupFilter f = new GroupFilter(include, "");
      assertEquals(expected, f.matches(group));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a+b", "a(b)", "a$b", "a^b", "a{1}b", "a\\b"})
    void groupIdsWithRegexMetacharsMatchLiterally(String group) {
      GroupFilter f = new GroupFilter(group, "");
      assertTrue(f.matches(group), "literal pattern should match itself");
    }

    @Test
    void regexMetacharsInGroupIdsDoNotThrow() {
      assertDoesNotThrow(() -> new GroupFilter("a+b,a(b),a{1}b", "a$b,a^b"));
    }

    @Test
    void pipeStaysLiteral_backCompat() {
      // back-compat: `|` in a glob is escaped, so "a|b" matches the literal "a|b",
      // not "a" or "b". Pre-existing user configs depending on this keep working.
      GroupFilter f = new GroupFilter("a|b", "");
      assertTrue(f.matches("a|b"));
      assertFalse(f.matches("a"));
      assertFalse(f.matches("b"));
    }
  }

  @Nested
  class IncludeDisjunction {

    @Test
    void twoFamilies_bothMatch_othersRejected() {
      GroupFilter f = new GroupFilter("ingest*,categorize*", "");
      assertTrue(f.matches("ingest-foo"));
      assertTrue(f.matches("ingest"));
      assertTrue(f.matches("categorize-bar"));
      assertFalse(f.matches("enrich-x"));
      assertFalse(f.matches("foo"));
    }

    @Test
    void threeLiterals_exactMatchEach() {
      GroupFilter f = new GroupFilter("foo,bar,baz", "");
      assertTrue(f.matches("foo"));
      assertTrue(f.matches("bar"));
      assertTrue(f.matches("baz"));
      assertFalse(f.matches("foobar"));
      assertFalse(f.matches("qux"));
    }

    @Test
    void whitespaceAroundSegmentsTrimmed() {
      GroupFilter f = new GroupFilter("  ingest* , categorize*  ", "");
      assertTrue(f.matches("ingest-foo"));
      assertTrue(f.matches("categorize-bar"));
      assertFalse(f.matches("enrich-x"));
    }

    @Test
    void emptySegmentsSkipped_notTreatedAsMatchAll() {
      GroupFilter f = new GroupFilter("ingest*,,categorize*", "");
      assertTrue(f.matches("ingest-foo"));
      assertTrue(f.matches("categorize-bar"));
      assertFalse(f.matches("enrich-x"));
    }

    @Test
    void duplicateSegmentsBehaveSameAsSingle() {
      GroupFilter dup = new GroupFilter("foo,foo", "");
      GroupFilter single = new GroupFilter("foo", "");
      assertEquals(single.matches("foo"), dup.matches("foo"));
      assertEquals(single.matches("bar"), dup.matches("bar"));
    }

    @Test
    void singleSegmentLegacyValue_unchangedBehavior() {
      // A user's existing config with a single glob must behave exactly as before.
      GroupFilter f = new GroupFilter("prod-*", "");
      assertTrue(f.matches("prod-foo"));
      assertFalse(f.matches("dev-foo"));
    }
  }

  @Nested
  class Exclude {

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", ",,,", " , "})
    void blankExclude_excludesNothing(String exclude) {
      GroupFilter f = new GroupFilter("*", exclude);
      assertTrue(f.matches("debug-foo"));
      assertTrue(f.matches("anything"));
    }

    @Test
    void nullExclude_excludesNothing() {
      GroupFilter f = new GroupFilter("*", null);
      assertTrue(f.matches("debug-foo"));
    }

    @Test
    void singleExclude_withDefaultInclude() {
      GroupFilter f = new GroupFilter("*", "debug-*");
      assertFalse(f.matches("debug-foo"));
      assertTrue(f.matches("prod-foo"));
    }

    @Test
    void threeExcludeFamilies_allRejected() {
      GroupFilter f = new GroupFilter("*", "debug-*,canary-*,*-shadow");
      assertFalse(f.matches("debug-foo"));
      assertFalse(f.matches("canary-foo"));
      assertFalse(f.matches("foo-shadow"));
      assertTrue(f.matches("prod-foo"));
    }

    @Test
    void excludeOnly_blankInclude_stillApplies() {
      GroupFilter f = new GroupFilter("", "debug-*");
      assertFalse(f.matches("debug-foo"));
      assertTrue(f.matches("anything-else"));
    }

    @Test
    void includeAndExcludeBothMatch_excludeWins() {
      GroupFilter f = new GroupFilter("prod-*", "prod-debug-*");
      assertFalse(f.matches("prod-debug-foo"));
      assertTrue(f.matches("prod-foo"));
    }

    @Test
    void excludeWithWhitespaceAndEmptySegmentsTolerated() {
      GroupFilter f = new GroupFilter("*", "  debug-* , , *-shadow  ");
      assertFalse(f.matches("debug-foo"));
      assertFalse(f.matches("foo-shadow"));
      assertTrue(f.matches("prod-foo"));
    }

    @Test
    void excludeStar_excludesEverything() {
      // Degenerate but defined: a literal `*` segment in the exclude list excludes all.
      GroupFilter f = new GroupFilter("*", "*");
      assertFalse(f.matches("anything"));
      assertFalse(f.matches(""));
    }
  }

  @Nested
  class Combined {

    @ParameterizedTest
    @CsvSource({
      "'*',                    '',                'anything',         true",
      "'*',                    'debug-*',         'debug-foo',        false",
      "'*',                    'debug-*',         'prod-foo',         true",
      "'prod-*',               'prod-debug-*',    'prod-debug-x',     false",
      "'prod-*',               'prod-debug-*',    'prod-x',           true",
      "'prod-*',               'prod-debug-*',    'dev-x',            false",
      "'ingest*,categorize*',  '*-shadow',        'ingest-shadow',    false",
      "'ingest*,categorize*',  '*-shadow',        'ingest-main',      true",
      "'ingest*,categorize*',  '*-shadow',        'categorize-main',  true",
      "'ingest*,categorize*',  '*-shadow',        'enrich-main',      false",
      "'',                     'debug-*',         'debug-foo',        false",
      "'',                     'debug-*',         'anything',         true",
      "'prod-*,canary-*',      'canary-*',        'canary-1',         false",
      "'prod-*,canary-*',      'canary-*',        'prod-1',           true"
    })
    void scenarios(String include, String exclude, String group, boolean expected) {
      GroupFilter f = new GroupFilter(include, exclude);
      assertEquals(expected, f.matches(group),
        () -> "include='" + include + "', exclude='" + exclude + "', group='" + group + "'");
    }
  }

  @Nested
  class EdgeCases {

    @Test
    void emptyGroupId_matchesStar() {
      GroupFilter f = new GroupFilter("*", "");
      assertTrue(f.matches(""));
    }

    @Test
    void emptyGroupId_rejectedByNonMatchingGlob() {
      GroupFilter f = new GroupFilter("prod-*", "");
      assertFalse(f.matches(""));
    }

    @Test
    void unicodeGroupId() {
      GroupFilter f = new GroupFilter("grüße-*", "");
      assertTrue(f.matches("grüße-prod"));
      assertFalse(f.matches("hello-prod"));
    }

    @Test
    void veryLongGroupId() {
      String longId = "prod-" + "a".repeat(1024);
      GroupFilter f = new GroupFilter("prod-*", "");
      assertTrue(f.matches(longId));
    }

    @Test
    void matchesIsPure_repeatedCallsSameResult() {
      GroupFilter f = new GroupFilter("ingest*,categorize*", "*-shadow");
      for (int i = 0; i < 5; i++) {
        assertTrue(f.matches("ingest-main"));
        assertFalse(f.matches("ingest-shadow"));
        assertFalse(f.matches("enrich-main"));
      }
    }

    @Test
    void descriptionForBlankInputs() {
      GroupFilter f = new GroupFilter("", "");
      assertEquals("*", f.includeDescription());
      assertEquals("(none)", f.excludeDescription());
    }

    @Test
    void descriptionForRawInputs() {
      GroupFilter f = new GroupFilter("ingest*,categorize*", "debug-*");
      assertEquals("ingest*,categorize*", f.includeDescription());
      assertEquals("debug-*", f.excludeDescription());
    }
  }
}
