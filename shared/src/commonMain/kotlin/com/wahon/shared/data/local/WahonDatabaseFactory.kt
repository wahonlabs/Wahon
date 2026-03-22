package com.wahon.shared.data.local

class WahonDatabaseFactory(
    private val driverFactory: DatabaseDriverFactory,
) {
    fun create(): WahonDatabase = WahonDatabase(driverFactory.createDriver())
}
