package com.example.timedmusicplayer.data.db.mapper

import com.example.timedmusicplayer.data.db.entity.CloudSourceEntity
import com.example.timedmusicplayer.domain.model.CloudSource

fun CloudSourceEntity.toModel() = CloudSource(id, name, url, coverUrl)

fun CloudSource.toEntity() = CloudSourceEntity(id, name, url, coverUrl)
