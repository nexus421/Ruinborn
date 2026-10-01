package bayern.kickner.ruinborn.client.render

import bayern.kickner.ruinborn.client.render.gfx.BuildingSprite
import bayern.kickner.ruinborn.client.render.gfx.IconSprite
import bayern.kickner.ruinborn.client.render.gfx.UnitSprite
import bayern.kickner.ruinborn.shared.model.BuildingType
import bayern.kickner.ruinborn.shared.model.Cosmetic
import bayern.kickner.ruinborn.shared.model.HeroId
import bayern.kickner.ruinborn.shared.model.ItemId
import bayern.kickner.ruinborn.shared.model.Resource
import bayern.kickner.ruinborn.shared.model.UnitType
import com.badlogic.gdx.graphics.Color

/** Mapping game object → sprite. */
object SpriteMap {
    fun building(t: BuildingType): BuildingSprite = when (t) {
        BuildingType.HQ -> BuildingSprite.HQ
        BuildingType.WALL -> BuildingSprite.WALL
        BuildingType.WAREHOUSE -> BuildingSprite.WAREHOUSE
        BuildingType.FARM -> BuildingSprite.FARM
        BuildingType.SAWMILL -> BuildingSprite.SAWMILL
        BuildingType.STEEL_MILL -> BuildingSprite.STEEL_MILL
        BuildingType.BARRACKS -> BuildingSprite.BARRACKS
        BuildingType.FACTORY -> BuildingSprite.FACTORY
        BuildingType.RANGE -> BuildingSprite.RANGE
        BuildingType.HOSPITAL -> BuildingSprite.HOSPITAL
        BuildingType.LAB -> BuildingSprite.LAB
        BuildingType.RALLY_POINT -> BuildingSprite.RALLY_POINT
        BuildingType.ALLIANCE_CENTER -> BuildingSprite.ALLIANCE_CENTER
    }

    fun field(r: Resource): BuildingSprite = when (r) {
        Resource.FOOD -> BuildingSprite.FIELD_FOOD
        Resource.WOOD -> BuildingSprite.FIELD_WOOD
        Resource.STEEL -> BuildingSprite.FIELD_STEEL
    }

    fun unit(t: UnitType): UnitSprite = when (t) {
        UnitType.INFANTRY -> UnitSprite.SOLDIER
        UnitType.VEHICLE -> UnitSprite.JEEP
        UnitType.SHOOTER -> UnitSprite.SHOOTER
    }

    fun hero(h: HeroId): IconSprite = when (h) {
        HeroId.RHEA -> IconSprite.HERO_RHEA
        HeroId.VIKTOR -> IconSprite.HERO_VIKTOR
        HeroId.KAYA -> IconSprite.HERO_KAYA
        HeroId.BROCK -> IconSprite.HERO_BROCK
        HeroId.NOVA -> IconSprite.HERO_NOVA
    }

    fun resource(r: Resource): IconSprite = when (r) {
        Resource.FOOD -> IconSprite.FOOD
        Resource.WOOD -> IconSprite.WOOD
        Resource.STEEL -> IconSprite.STEEL
    }

    fun item(i: ItemId): IconSprite = when {
        i.isSpeedup -> IconSprite.SPEEDUP
        i.isChest -> when {
            i.name.startsWith("RES_FOOD") -> IconSprite.FOOD
            i.name.startsWith("RES_WOOD") -> IconSprite.WOOD
            else -> IconSprite.STEEL
        }
        i.isShield -> IconSprite.SHIELD
        i.isHeroBook -> IconSprite.BOOK
        else -> IconSprite.RELOCATE
    }

    fun buildingIcon(t: BuildingType): IconSprite = when (t) {
        BuildingType.HQ -> IconSprite.BASE
        BuildingType.WALL -> IconSprite.SHIELD
        BuildingType.WAREHOUSE -> IconSprite.CRATE
        BuildingType.FARM -> IconSprite.FOOD
        BuildingType.SAWMILL -> IconSprite.WOOD
        BuildingType.STEEL_MILL -> IconSprite.STEEL
        BuildingType.BARRACKS, BuildingType.FACTORY, BuildingType.RANGE -> IconSprite.TROOPS
        BuildingType.HOSPITAL -> IconSprite.HOSPITAL
        BuildingType.LAB -> IconSprite.RESEARCH
        BuildingType.RALLY_POINT -> IconSprite.SWORD
        BuildingType.ALLIANCE_CENTER -> IconSprite.ALLIANCE
    }

    /** Tint of ground and HQ per base skin (cosmetic only, no game stats). */
    fun skinTint(c: Cosmetic?): Color = when (c) {
        Cosmetic.SKIN_TIN -> Color(0.80f, 0.88f, 1.0f, 1f)
        Cosmetic.SKIN_FORT -> Color(0.78f, 0.74f, 0.70f, 1f)
        Cosmetic.SKIN_CITADEL -> Color(1.0f, 0.90f, 0.62f, 1f)
        else -> Color.WHITE
    }

    /** Color of a profile frame. */
    fun frameColor(c: Cosmetic?): Color = when (c) {
        Cosmetic.FRAME_BULWARK -> Color(0.55f, 0.66f, 0.80f, 1f)
        Cosmetic.FRAME_NEST -> Color(0.40f, 0.78f, 0.36f, 1f)
        Cosmetic.FRAME_PLAGUE -> Color(0.70f, 0.40f, 0.85f, 1f)
        Cosmetic.FRAME_LEGEND -> Color(1.0f, 0.80f, 0.25f, 1f)
        else -> Color(0.55f, 0.57f, 0.60f, 1f)
    }
}
