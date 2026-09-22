package com.kirjasto.kirjastobotti

/**
 * Provider-neutral contract used by the catalogue UI.  Connectors map their
 * native response to these types; the UI never needs to know the vendor.
 */
data class BookResult(
    val id: String,
    val title: String,
    val author: String? = null,
    val format: String? = null,
    val language: String? = null,
    val coverUrl: String? = null,
    val localAvailability: Availability = Availability.CHECK_WITH_STAFF,
    val locations: List<BranchLocation> = emptyList(),
    val officialDetailsUrl: String? = null
)

data class BranchLocation(
    val branchName: String,
    val status: Availability,
    val shelfCode: String? = null,
    val navigationDestinationId: String? = null
)

enum class Availability {
    AVAILABLE_HERE,
    ON_LOAN,
    AVAILABLE_AT_ANOTHER_BRANCH,
    CHECK_WITH_STAFF
}
