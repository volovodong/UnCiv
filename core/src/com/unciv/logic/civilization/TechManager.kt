package com.unciv.logic.civilization


import com.unciv.models.gamebasics.GameBasics
import com.unciv.models.gamebasics.Technology
import java.util.*

class TechManager {
    @Transient
    lateinit var civInfo: CivilizationInfo

    var freeTechs = 0
    var techsResearched = HashSet<String>()
    /* When moving towards a certain tech, the user doesn't have to manually pick every one. */
    var techsToResearch = ArrayList<String>()
    private var techsInProgress = HashMap<String, Int>()

    private fun getCurrentTechnology(): Technology = GameBasics.Technologies[currentTechnology()]!!

    fun getAmountResearchedText(): String =
            if (currentTechnology() == null) ""
            else "(" + researchOfTech(currentTechnology()!!) + "/" + getCurrentTechnology().cost + ")"


    fun currentTechnology(): String? {
        if (techsToResearch.isEmpty()) return null
        else return techsToResearch[0]
    }

    fun researchOfTech(TechName: String?): Int {
        if (techsInProgress.containsKey(TechName)) return techsInProgress[TechName]!!
        else return 0
    }


    fun isResearched(TechName: String): Boolean = techsResearched.contains(TechName)

    fun canBeResearched(TechName: String): Boolean {
        return GameBasics.Technologies[TechName]!!.prerequisites.all { isResearched(it) }
    }

    fun nextTurn(scienceForNewTurn: Int) {
        val currentTechnology = currentTechnology()
        if (currentTechnology == null) return
        techsInProgress[currentTechnology] = researchOfTech(currentTechnology) + scienceForNewTurn
        if (techsInProgress[currentTechnology]!! < getCurrentTechnology().cost)
            return
        // We finished it!
        techsInProgress.remove(currentTechnology)
        techsToResearch.remove(currentTechnology)
        techsResearched.add(currentTechnology)
        civInfo.gameInfo.addNotification("Research of $currentTechnology has completed!", null)

        val revealedResource = GameBasics.TileResources.values.firstOrNull { currentTechnology == it.revealedBy }

        if (revealedResource == null) return
        for (tileInfo in civInfo.gameInfo.tileMap.values
                .filter { it.resource == revealedResource.name && civInfo.civName == it.owner }) {

            val closestCityTile = civInfo.gameInfo.tileMap.getTilesInDistance(tileInfo.position, 4)
                    .firstOrNull { it.isCityCenter }
            if (closestCityTile != null) {
                civInfo.gameInfo.addNotification(
                        revealedResource.name + " revealed near " + closestCityTile.city!!.name, tileInfo.position)
                break
            }
        }
    }
}

