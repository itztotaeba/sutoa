package com.kinyo.sutoa.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** Separate Retrofit base URLs per source plugin. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FdroidApiQualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GithubApiQualifier
