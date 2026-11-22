package com.fairtix.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V55 renames the V27/V29 demo seed events and venues to fictional names and touches only seed
 * rows; running it a second time changes nothing, and it renames what a fresh-stack V29 re-seed
 * inserts (the scripts/demo-seed.sh path). The script's own guard, which re-seeds a group only
 * when none of its events exist, is not covered here. Runs on a scratch database, because the
 * shared IT database is already at the latest version with the seed rows removed.
 */
class DemoSeedRenameMigrationIT extends PostgresIT {

  private static final List<String> NEW_TITLES = List.of(
      "Crescent Jazz Weekend", "Gulf Coast Rodeo Night", "Bayou Pelicans vs Harbor Foxes",
      "Nova Lane - Arena Tour Finale", "The Velvet Static - Live", "Sunfield Festival - Day 1",
      "Basement Comedy - Late Show");

  private static final List<String> NEW_VENUES = List.of(
      "Crescent Demo Arena", "Bayou Demo Center", "Riverfront Demo Dome", "Midtown Demo Garden",
      "Harbor Demo Stadium", "Sunfield Demo Grounds", "The Demo Basement");

  private static final List<String> OLD_VENUES = List.of(
      "Smoothie King Center", "Toyota Center", "Caesars Superdome", "Madison Square Garden",
      "SoFi Stadium", "Empire Polo Club", "Comedy Cellar");

  @Autowired private JdbcTemplate jdbcTemplate;

  private final String database = "v55_check_" + UUID.randomUUID().toString().replace("-", "");
  private DriverManagerDataSource scratch;
  private JdbcTemplate scratchJdbc;

  @BeforeEach
  void createScratchDatabase() {
    jdbcTemplate.execute("CREATE DATABASE " + database);
    scratch = new DriverManagerDataSource(
        "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database,
        POSTGRES.getUsername(), POSTGRES.getPassword());
    scratchJdbc = new JdbcTemplate(scratch);
  }

  @AfterEach
  void dropScratchDatabase() {
    jdbcTemplate.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
  }

  @Test
  void v55RenamesTheSeedRowsOnlyAndCanBeRerun() throws IOException {
    seedWithAnOrganizer();
    long seatsBefore = seatCount();
    UUID decoyVenue = insertVenue("Organizer Hall", "1 Main St");
    UUID decoyEvent = copyEventTo("Jazz Fest 2026", decoyVenue);

    migrateTo("55");

    assertThat(eventTitles()).containsExactlyInAnyOrderElementsOf(withDecoy());
    assertThat(venueNames()).containsAll(NEW_VENUES).doesNotContainAnyElementsOf(OLD_VENUES);
    assertThat(scratchJdbc.queryForObject("SELECT address FROM venues WHERE name = 'Midtown Demo Garden'",
        String.class)).isEqualTo("400 Demo Street");
    assertThat(scratchJdbc.queryForObject("SELECT title FROM events WHERE id = ?", String.class, decoyEvent))
        .isEqualTo("Jazz Fest 2026");
    assertThat(seatCount()).isEqualTo(seatsBefore);

    scratchJdbc.execute(sql("V55__fictional_demo_seed_names.sql"));

    assertThat(eventTitles()).containsExactlyInAnyOrderElementsOf(withDecoy());
    assertThat(venueNames()).containsAll(NEW_VENUES);
  }

  @Test
  void aVenueWhoseNewNameIsTakenKeepsItsName() {
    seedWithAnOrganizer();
    insertVenue("Bayou Demo Center", "9 Other Rd");

    migrateTo("55");

    assertThat(venueNames()).contains("Toyota Center", "Bayou Demo Center");
    assertThat(eventTitles()).containsExactlyInAnyOrderElementsOf(NEW_TITLES);
  }

  /** Names alone are not enough: a seed name at a different address is somebody else's venue. */
  @Test
  void aSeedNamedVenueAtAnotherAddressAndItsSeedTitledEventKeepTheirNames() {
    seedWithAnOrganizer();
    scratchJdbc.update("UPDATE venues SET address = '9 Elsewhere Rd' WHERE name = 'Toyota Center'");

    migrateTo("55");

    assertThat(scratchJdbc.queryForObject("SELECT address FROM venues WHERE name = 'Toyota Center'",
        String.class)).isEqualTo("9 Elsewhere Rd");
    assertThat(eventTitles()).contains("Houston Rodeo Night").doesNotContain("Gulf Coast Rodeo Night");
    assertThat(venueNames()).doesNotContain("Bayou Demo Center");
  }

  /** Venues are global: a seed venue that a non-seed event uses must not be renamed under it. */
  @Test
  void aSeedVenueUsedByANonSeedEventKeepsItsNameAndAddress() {
    seedWithAnOrganizer();
    UUID garden = scratchJdbc.queryForObject("SELECT id FROM venues WHERE name = 'Madison Square Garden'",
        UUID.class);
    UUID organizerEvent = copyEventTo("Jazz Fest 2026", garden, "Organizer Gig");

    migrateTo("55");

    assertThat(scratchJdbc.queryForObject("SELECT address FROM venues WHERE id = ?", String.class, garden))
        .isEqualTo("4 Pennsylvania Plaza");
    assertThat(scratchJdbc.queryForObject("SELECT name FROM venues WHERE id = ?", String.class, garden))
        .isEqualTo("Madison Square Garden");
    assertThat(scratchJdbc.queryForObject("SELECT title FROM events WHERE id = ?", String.class, organizerEvent))
        .isEqualTo("Organizer Gig");
    assertThat(venueNames()).contains("Crescent Demo Arena", "Bayou Demo Center")
        .doesNotContain("Midtown Demo Garden");
  }

  /** The demo-seed.sh path: V29 seeds nothing before any user exists, so the script re-runs it. */
  @Test
  void reseedingV29AfterV55AndRerunningV55LeavesNoRealNames() throws IOException {
    migrateTo("55");
    insertAdmin();

    scratchJdbc.execute(sql("V29__seed_extra_demo_events.sql"));
    scratchJdbc.execute(sql("V55__fictional_demo_seed_names.sql"));

    assertThat(eventTitles()).containsExactlyInAnyOrderElementsOf(NEW_TITLES);
    assertThat(venueNames()).containsAll(NEW_VENUES).doesNotContainAnyElementsOf(OLD_VENUES);
  }

  /** Migrates with a user in place before V27, so both seed migrations insert their events. */
  private void seedWithAnOrganizer() {
    migrateTo("26");
    insertAdmin();
    migrateTo("54");
    assertThat(eventTitles()).hasSize(7).contains("Saints vs Panthers", "Beyoncé Renaissance Tour");
  }

  private void insertAdmin() {
    UUID id = UUID.randomUUID();
    scratchJdbc.update("INSERT INTO users (id, email, password, role) VALUES (?, ?, 'x', 'ADMIN')",
        id, "admin-" + id + "@example.test");
  }

  private UUID insertVenue(String name, String address) {
    UUID id = UUID.randomUUID();
    scratchJdbc.update("INSERT INTO venues (id, name, address, city, country) VALUES (?, ?, ?, 'Austin', 'US')",
        id, name, address);
    return id;
  }

  /** A non-seed event: a copy of a seed event, with a new id, at another venue. */
  private UUID copyEventTo(String title, UUID venueId) {
    return copyEventTo(title, venueId, title);
  }

  private UUID copyEventTo(String title, UUID venueId, String newTitle) {
    UUID id = UUID.randomUUID();
    scratchJdbc.update("INSERT INTO events SELECT (jsonb_populate_record(e,"
            + " jsonb_build_object('id', ?::text, 'venue_id', ?::text, 'title', ?::text))).*"
            + " FROM events e WHERE e.title = ?",
        id.toString(), venueId.toString(), newTitle, title);
    return id;
  }

  private List<String> withDecoy() {
    return Stream.concat(NEW_TITLES.stream(), Stream.of("Jazz Fest 2026")).toList();
  }

  private List<String> eventTitles() {
    return scratchJdbc.queryForList("SELECT title FROM events", String.class);
  }

  private List<String> venueNames() {
    return scratchJdbc.queryForList("SELECT name FROM venues", String.class);
  }

  private long seatCount() {
    return scratchJdbc.queryForObject("SELECT count(*) FROM seats", Long.class);
  }

  private static String sql(String migration) throws IOException {
    return new ClassPathResource("db/migration/" + migration).getContentAsString(StandardCharsets.UTF_8);
  }

  private void migrateTo(String version) {
    Flyway.configure()
        .dataSource(scratch)
        .locations("classpath:db/migration")
        .target(version)
        .load()
        .migrate();
  }
}
