package com.unciv.logic.city

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.map.RoadStatus
import com.unciv.logic.map.TileInfo
import com.unciv.logic.map.TileMap
import com.unciv.models.Counter
import com.unciv.models.gamebasics.GameBasics
import com.unciv.models.gamebasics.tile.ResourceType
import com.unciv.models.gamebasics.tile.TileResource
import com.unciv.models.stats.Stats
import kotlin.math.min

class CityInfo {
    @Transient lateinit var civInfo: CivilizationInfo
    @Transient var isConnectedToCapital = false
    @Transient lateinit var ccenterTile:TileInfo  // 'cached' for better performance

    var location: Vector2 = Vector2.Zero
    var name: String = ""
    var health = 200
    var resistanceCounter = 0

    var population = PopulationManager()
    var cityConstructions = CityConstructions()
    var expansion = CityExpansionManager()
    var cityStats = CityStats()

    var tiles = HashSet<Vector2>()
    var workedTiles = HashSet<Vector2>()
    var isBeingRazed = false


    constructor()   // for json parsing, we need to have a default constructor
    constructor(civInfo: CivilizationInfo, cityLocation: Vector2) {
        this.civInfo = civInfo
        this.location = cityLocation
        setTransients()

        // Since cities can be captures between civilizations,
        // we need to check which other cities exist globally and name accordingly
        val allExistingCityNames = civInfo.gameInfo.civilizations.flatMap { it.cities }.map { it.name }.toHashSet()
        val probablyName = civInfo.getNation().cities.firstOrNull { !allExistingCityNames.contains(it) }
        if(probablyName!=null) name=probablyName
        else {
            val newName = civInfo.getNation().cities.map { "New $it" }.firstOrNull{ !allExistingCityNames.contains(it) }
            if(newName!=null) name=newName
            else name = civInfo.getNation().cities.map { "Newer $it" }.first{ !allExistingCityNames.contains(it) }
        }

        civInfo.cities = civInfo.cities.toMutableList().apply { add(this@CityInfo) }
        civInfo.addNotification("[$name] has been founded!", cityLocation, Color.PURPLE)

        if (civInfo.policies.isAdopted("Legalism") && civInfo.cities.size <= 4) cityConstructions.addCultureBuilding()
        if (civInfo.cities.size == 1) {
            cityConstructions.addBuilding("Palace")
            cityConstructions.currentConstruction = "Worker" // Default for first city only!
        }

        expansion.reset()

        val tile = getCenterTile()
        tile.roadStatus = RoadStatus.Railroad
        if (listOf("Forest", "Jungle", "Marsh").contains(tile.terrainFeature))
            tile.terrainFeature = null

        population.autoAssignPopulation()
        cityStats.update()
    }

    //region pure functions
    fun clone(): CityInfo {
        val toReturn = CityInfo()
        toReturn.location=location
        toReturn.name=name
        toReturn.health=health
        toReturn.population = population.clone()
        toReturn.cityConstructions=cityConstructions.clone()
        toReturn.expansion = expansion.clone()
        toReturn.tiles.addAll(tiles)
        toReturn.workedTiles.addAll(workedTiles)
        toReturn.isBeingRazed=isBeingRazed
        toReturn.isConnectedToCapital = isConnectedToCapital
        return toReturn
    }

    internal val tileMap: TileMap
        get() = civInfo.gameInfo.tileMap

    fun getCenterTile(): TileInfo = ccenterTile
    fun getTiles(): List<TileInfo> = tiles.map { tileMap[it] }
    fun getTilesInRange(): List<TileInfo> = getCenterTile().getTilesInDistance( 3)

    fun getCityResources(): Counter<TileResource> {
        val cityResources = Counter<TileResource>()

        for (tileInfo in getTiles().filter { it.resource != null }) {
            val resource = tileInfo.getTileResource()
            if(resource.revealedBy!=null && !civInfo.tech.isResearched(resource.revealedBy!!)) continue
            if (resource.improvement == tileInfo.improvement || tileInfo.isCityCenter()){
                var amountToAdd = 1
                if(resource.resourceType == ResourceType.Strategic){
                    amountToAdd = 2
                    if(civInfo.policies.isAdopted("Facism")) amountToAdd*=2
                    if(civInfo.getNation().unique=="Strategic Resources provide +1 Production, and Horses, Iron and Uranium Resources provide double quantity"
                        && resource.name in listOf("Horses","Iron","Uranium"))
                        amountToAdd*=2
                }
                if(resource.resourceType == ResourceType.Luxury
                        && getBuildingUniques().contains("Provides 1 extra copy of each improved luxury resource near this City"))
                    amountToAdd*=2
                cityResources.add(resource, amountToAdd)
            }

        }

        for (building in cityConstructions.getBuiltBuildings().filter { it.requiredResource != null }) {
            val resource = GameBasics.TileResources[building.requiredResource]
            cityResources.add(resource, -1)
        }

        return cityResources
    }

    fun getBuildingUniques(): List<String> = cityConstructions.getBuiltBuildings().flatMap { it.uniques }

    fun getGreatPersonPoints(): Stats {
        var greatPersonPoints = population.specialists.times(3f)

        for (building in cityConstructions.getBuiltBuildings())
            if (building.greatPersonPoints != null)
                greatPersonPoints.add(building.greatPersonPoints!!)

        if (civInfo.getBuildingUniques().contains("+33% great person generation in all cities"))
            greatPersonPoints = greatPersonPoints.times(1.33f)
        if (civInfo.policies.isAdopted("Entrepreneurship"))
            greatPersonPoints.gold *= 1.25f
        if (civInfo.policies.isAdopted("Freedom"))
            greatPersonPoints = greatPersonPoints.times(1.25f)

        return greatPersonPoints
    }

    fun isCapital() = cityConstructions.isBuilt("Palace")

    internal fun getMaxHealth(): Int {
        return 200 + cityConstructions.getBuiltBuildings().sumBy { it.cityHealth }
    }

    override fun toString(): String {return name} // for debug
    //endregion

    //region state-changing functions
    fun setTransients() {
        ccenterTile = tileMap[location]
        population.cityInfo = this
        expansion.cityInfo = this
        expansion.setTransients()
        cityStats.cityInfo = this
        cityConstructions.cityInfo = this
        cityConstructions.setTransients()
    }

    fun endTurn() {
        val stats = cityStats.currentCityStats
        if (cityConstructions.currentConstruction == CityConstructions.Settler && stats.food > 0) {
            stats.production += stats.food
            stats.food = 0f
        }


        cityConstructions.nextTurn(stats)
        expansion.nextTurn(stats.culture)
        if(isBeingRazed){
            population.population--
            if(population.population<=0){ // there are strange cases where we geet to -1
                civInfo.addNotification("[$name] has been razed to the ground!",location, Color.RED)
                destroyCity()
                if(isCapital() && civInfo.cities.isNotEmpty()) // Yes, we actually razed the capital. Some people do this.
                    civInfo.cities.first().cityConstructions.addBuilding("Palace")
            }
        }
        else population.nextTurn(stats.food)
        if (resistanceCounter > 0) resistanceCounter--

        if(this in civInfo.cities) { // city was not destroyed
            health = min(health + 20, getMaxHealth())
            population.unassignExtraPopulation()
        }
    }

    fun destroyCity() {
        // Edge case! What if a water unit is in a city, and you raze the city?
        // Well, the water unit has to return to the water!
        for(unit in getCenterTile().getUnits())
            if(!unit.canPassThrough(getCenterTile()))
                unit.movementAlgs().teleportToClosestMoveableTile()

        civInfo.cities = civInfo.cities.toMutableList().apply { remove(this@CityInfo) }
        getTiles().forEach { expansion.relinquishOwnership(it) }
        getCenterTile().improvement="City ruins"
    }

    fun moveToCiv(newCivInfo: CivilizationInfo){
        civInfo.cities = civInfo.cities.toMutableList().apply { remove(this@CityInfo) }
        newCivInfo.cities = newCivInfo.cities.toMutableList().apply { add(this@CityInfo) }
        civInfo = newCivInfo

        // now that the tiles have changed, we need to reassign population
        workedTiles.filterNot { tiles.contains(it) }
                .forEach { workedTiles.remove(it); population.autoAssignPopulation() }

        // Remove all national wonders
        for(building in cityConstructions.getBuiltBuildings().filter { it.requiredBuildingInAllCities!=null })
            cityConstructions.removeBuilding(building.name)
        isBeingRazed=false
    }

    fun canAcquireTile(newTileInfo: TileInfo): Boolean {
        val owningCity = newTileInfo.getCity()
        if (owningCity!=null && owningCity!=this
                && newTileInfo.getOwner()!!.isPlayerCivilization()
                && newTileInfo.arialDistanceTo(getCenterTile()) <= 3
                && newTileInfo.neighbors.any{it.getCity()==this}) {
            return true
        }
        return false
    }
    //endregion
}