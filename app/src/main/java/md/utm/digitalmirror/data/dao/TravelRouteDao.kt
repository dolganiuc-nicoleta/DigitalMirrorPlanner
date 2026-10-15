package md.utm.digitalmirror.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import md.utm.digitalmirror.data.entity.TravelRoute

@Dao
interface TravelRouteDao {

    @Query("SELECT * FROM travel_route WHERE fromPlaceId = :from AND toPlaceId = :to AND profile = :profile")
    suspend fun get(from: Long, to: Long, profile: String): TravelRoute?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(route: TravelRoute)

    /** Drumurile care pornesc din sau ajung la locul dat (după ce locul s-a mutat sau s-a șters). */
    @Query("DELETE FROM travel_route WHERE fromPlaceId = :placeId OR toPlaceId = :placeId")
    suspend fun deleteForPlace(placeId: Long)
}