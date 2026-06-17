package com.ditchoom.onebrc

/** A weather station and its mean temperature (°C), used to generate the dataset. */
data class Station(
    val name: String,
    val mean: Double,
)

/**
 * A representative set of weather stations with mean temperatures, modeled on the official 1BRC
 * station list. This is a faithful *shape* of the challenge (varied cardinality, a multi-byte name,
 * a wide temperature spread), not a byte-identical copy of the official measurements — which only
 * matters when diffing against the public leaderboard's exact output hash.
 */
object StationData {
    val stations: List<Station> =
        listOf(
            Station("Abha", 18.0),
            Station("Abidjan", 26.0),
            Station("Accra", 26.4),
            Station("Addis Ababa", 16.0),
            Station("Adelaide", 17.3),
            Station("Algiers", 18.2),
            Station("Amsterdam", 10.2),
            Station("Anchorage", 2.8),
            Station("Athens", 19.2),
            Station("Auckland", 15.2),
            Station("Bangkok", 28.6),
            Station("Barcelona", 18.2),
            Station("Beijing", 12.9),
            Station("Berlin", 10.3),
            Station("Bogotá", 15.4),
            Station("Brussels", 10.5),
            Station("Bucharest", 10.8),
            Station("Cairo", 21.4),
            Station("Cape Town", 16.2),
            Station("Chicago", 9.8),
            Station("Copenhagen", 9.1),
            Station("Dakar", 24.0),
            Station("Dublin", 9.8),
            Station("Hamburg", 9.7),
            Station("Helsinki", 5.9),
            Station("Honolulu", 25.4),
            Station("Istanbul", 13.9),
            Station("Jakarta", 26.7),
            Station("Lagos", 26.8),
            Station("Lima", 18.2),
            Station("London", 11.3),
            Station("Madrid", 15.0),
            Station("Moscow", 5.8),
            Station("Nairobi", 17.8),
            Station("New York City", 12.9),
            Station("Reykjavík", 4.3),
            Station("Riyadh", 26.0),
            Station("Rome", 15.2),
            Station("San Francisco", 14.6),
            Station("Singapore", 27.0),
            Station("Stockholm", 6.6),
            Station("Sydney", 17.7),
            Station("Tokyo", 15.4),
            Station("Toronto", 9.4),
            Station("Vancouver", 10.4),
            Station("Yellowknife", -4.3),
        )
}
