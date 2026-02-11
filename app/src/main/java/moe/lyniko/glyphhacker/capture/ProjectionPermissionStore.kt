package moe.lyniko.glyphhacker.capture

import android.content.Intent

data class ProjectionPermission(
    val resultCode: Int,
    val data: Intent,
)

object ProjectionPermissionStore {
    @Volatile
    private var cachedPermission: ProjectionPermission? = null

    fun set(permission: ProjectionPermission) {
        cachedPermission = ProjectionPermission(
            resultCode = permission.resultCode,
            data = Intent(permission.data),
        )
    }

    fun get(): ProjectionPermission? {
        val permission = cachedPermission ?: return null
        return ProjectionPermission(permission.resultCode, Intent(permission.data))
    }

    fun consume(): ProjectionPermission? {
        val permission = cachedPermission ?: return null
        cachedPermission = null
        return ProjectionPermission(permission.resultCode, Intent(permission.data))
    }

    fun clear() {
        cachedPermission = null
    }
}
