/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dropbox.android.external.store4.impl

import com.dropbox.android.external.store4.ResponseOrigin
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.flow.multicast.Multicaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * This class maintains one and only 1 fetcher for a given [Key].
 *
 * Any value emitted by the fetcher is sent into the [sourceOfTruth] before it is dispatched.
 * If [sourceOfTruth] is `null`, [enablePiggyback] is set to true by default so that previous
 * fetcher requests receives values dispatched by later requests even if they don't share the
 * request.
 */
@FlowPreview
@ExperimentalCoroutinesApi
internal class FetcherController<Key, Input, Output>(
    /**
     * The [CoroutineScope] to use when collecting from the fetcher
     */
    private val scope: CoroutineScope,
    /**
     * The function that provides the actualy fetcher flow when needed
     */
    private val realFetcher: (Key) -> Flow<Input>,
    /**
     * [SourceOfTruth] to send the data each time fetcher dispatches a value. Can be `null` if
     * no [SourceOfTruth] is available.
     */
    private val sourceOfTruth: SourceOfTruthWithBarrier<Key, Input, Output>?,
    /**
     * When enabled, downstream collectors are never closed, instead, they are kept active to
     * receive values dispatched by fetchers created after them. This makes [FetcherController]
     * act like a [SourceOfTruth] in the lack of a [SourceOfTruth] provided by the developer.
     */
    private val enablePiggyback: Boolean = sourceOfTruth == null
) {
    private val fetchers = RefCountedResource(
        create = { key: Key ->
            Multicaster(
                scope = scope,
                bufferSize = 0,
                source = {
                    realFetcher(key).map {
                        StoreResponse.Data(
                            it,
                            origin = ResponseOrigin.Fetcher
                        ) as StoreResponse<Input>
                    }.catch {
                        emit(StoreResponse.Error(it, origin = ResponseOrigin.Fetcher))
                    }
                },
                piggybackingDownstream = enablePiggyback,
                onEach = { response ->
                    response.dataOrNull()?.let { input ->
                        sourceOfTruth?.write(key, input)
                    }
                }
            )
        },
        onRelease = { _: Key, multicaster: Multicaster<StoreResponse<Input>> ->
            multicaster.close()
        }
    )

    fun getFetcher(key: Key): Flow<StoreResponse<Input>> {
        return flow {
            val fetcher = fetchers.acquire(key)
            try {
                emitAll(fetcher.create())
            } finally {
                fetchers.release(key, fetcher)
            }
        }
    }

    // visible for testing
    internal suspend fun fetcherSize() = fetchers.size()
}
