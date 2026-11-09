package indi.renakoni.nextvol.data.local

import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.local.room.entity.UserDataEntity

/** Allows tests to hold one operation while retaining the real Room implementation. */
internal open class ForwardingUserDataDao(private val delegate: UserDataDao) : UserDataDao() {
    override fun submit(path: String, write: suspend () -> Unit) = delegate.submit(path, write)
    override suspend fun insert(path: String, group: String, type: String, value: String) =
        delegate.insert(path, group, type, value)
    override suspend fun insert(userDataEntity: UserDataEntity) = delegate.insert(userDataEntity)
    override suspend fun get(path: String) = delegate.get(path)
    override suspend fun update(path: String, group: String, type: String, transform: (String?) -> String) =
        delegate.update(path, group, type, transform)
    override fun getFlow(path: String) = delegate.getFlow(path)
    override fun getEntity(path: String) = delegate.getEntity(path)
    override fun getGroupValues(group: String) = delegate.getGroupValues(group)
    override suspend fun remove(path: String) = delegate.remove(path)
    override fun getAllEntities() = delegate.getAllEntities()
}
