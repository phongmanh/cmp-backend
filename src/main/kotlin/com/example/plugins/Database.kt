package com.example.plugins

import com.example.common.AppConfig
import com.example.common.createDataSource
import com.example.common.runMigrations
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.log
import org.jetbrains.exposed.sql.Database
import org.koin.ktor.ext.inject

fun Application.configureDatabase() {
    val appConfig by inject<AppConfig>()

    val dataSource = createDataSource(appConfig.database)
    if (appConfig.database.shouldRunMigrations) {
        log.info("Applying database migrations")
        runMigrations(dataSource)
    }
    Database.connect(dataSource)

    monitor.subscribe(ApplicationStopping) { dataSource.close() }
}
