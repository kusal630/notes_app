package com.premiumnotes.input

import kotlin.math.hypot
import kotlin.math.max

/**
 * Resting-hand / palm rejection layer that runs AFTER the size-based [PalmClassifier].
 *
 * The size classifier already rejects large palms. This layer handles the cases the size
 * classifier cannot: a resting hand frequently appears as several SMALL finger-sized
 * contacts (resting fingers, or the side of a hand) that are indistinguishable from a
 * writing fingertip by size alone. The only reliable signal is MOTION: the actual writer
 * moves like a stroke while the resting fingers stay put.
 *
 * Rules implemented here (see docs/palm-rejection.md):
 *  - Stationary contacts in a resting context (>= 3 contacts, near a screen edge, or a
 *    tight stationary cluster) are [ContactClassification.RESTING]: they never draw and
 *    never drive gestures.
 *  - A small contact that lands while resting contacts are already down — or as part of a
 *    multi-contact slap — is a [ContactClassification.CANDIDATE]: observed for a short
 *    window, promoted to [ContactClassification.WRITING] only when it moves like a stroke,
 *    demoted to [ContactClassification.RESTING] when it does not.
 *  - When two (or more) small contacts move together they stay [ContactClassification.FINGER]
 *    so two-finger pan/zoom keeps working even with a resting palm on the screen.
 *  - A locked writing pointer is sticky: pausing never cancels it. It is only cancelled
 *    when its SMOOTHED contact size grows into palm territory (with hysteresis), so a
 *    single digitizer spike never kills an in-progress stroke.
 *
 * Motion is scored over a sliding velocity window ([PalmRejectionSettings.velocityWindowMs],
 * kept within the 80–180 ms design budget):
 *  - "Stroke-like motion" is gated by the WINDOWED velocity, not raw instantaneous speed,
 *    so a slow, jittery resting hand is not confused with a deliberate stroke.
 *  - The promote threshold is adaptive: it is raised when the resting hand itself produces
 *    high windowed velocity (resting-noise EMA), so a hard-resting hand cannot drown out
 *    the writer.
 *  - A whole-hand slow drift shows up as a cluster of contacts moving together below the
 *    stroke threshold and is rejected as a hand shift, not a stroke.
 *
 * The tracker is stateless w.r.t. the base classifier: it only adds motion/timing/cluster
 * evidence on top of the size decision, so disabling [PalmRejectionSettings.restingHandModeEnabled]
 * restores the exact legacy behavior.
 */
class RestingHandTracker(private val capabilities: InputCapabilities) {

    /** Outcome of one frame of resting-hand analysis. */
    data class Result(
        /** Contacts with resting-hand adjustments applied. */
        val classified: List<ClassifiedContact>,
        /** A CANDIDATE that moved like a stroke and should now claim the writing lock. */
        val promoteCandidatePointerId: Int?,
        /** The locked writing pointer whose smoothed size grew palm-like and must be cancelled. */
        val cancelLockPointerId: Int?,
        /** Bounding boxes of resting clusters (>= 2 resting contacts), for the debug overlay. */
        val clusterBounds: List<ClusterBounds> = emptyList(),
    )

    private val pointerStates = HashMap<Int, PointerMotionState>()

    /**
     * Adaptive estimate of the resting hand's windowed-velocity noise (mm/s). EMA of the
     * velocity produced by currently-resting contacts; the stroke gate is
     * max(minPromoteVelocityMmPerSec, restingNoise * PROMOTE_VS_NOISE).
     */
    private var restingNoiseMmPerSec: Float = 0f

    /** Viewport size in screen px, set by the canvas (used for edge detection). */
    var viewportWidthPx: Float = 0f
    var viewportHeightPx: Float = 0f

    fun reset() {
        pointerStates.clear()
        restingNoiseMmPerSec = 0f
    }

    /**
     * Updates per-pointer motion/size state for the current frame, computes the resting
     * context (edge / cluster / multi-contact), and produces the adjusted classification
     * for every contact. [baseClassified] is the size-classifier output.
     */
    fun process(
        frame: InputFrame,
        baseClassified: List<ClassifiedContact>,
        activeWritingPointerId: Int?,
        settings: PalmRejectionSettings,
    ): Result {
        val nowNanos = frame.eventTimeNanos
        val restingEnabled = settings.palmRejectionEnabled && settings.restingHandModeEnabled
        val pxPerMm = capabilities.pxPerMm

        // --- 1. Update per-pointer motion + contact-size state. ------------------------
        val presentIds = HashSet<Int>(baseClassified.size)
        for (c in baseClassified) {
            val id = c.contact.pointerId
            presentIds += id
            val st = pointerStates[id]
            if (st == null) {
                val size = c.contact.maxDimMm
                val ns = PointerMotionState(
                    downTimeNanos = c.contact.downTimeNanos,
                    startX = c.contact.x,
                    startY = c.contact.y,
                    lastX = c.contact.x,
                    lastY = c.contact.y,
                    lastTimeNanos = c.contact.eventTimeNanos,
                    initialContactSizeMm = size,
                    smoothedContactSizeMm = size,
                    lastContactSizeMm = size,
                )
                ns.recentSamples.addLast(MotionSample(nowNanos, c.contact.x, c.contact.y))
                pointerStates[id] = ns
            } else {
                val dtMs = (nowNanos - st.lastTimeNanos) / 1_000_000L
                val distPx = hypot(c.contact.x - st.lastX, c.contact.y - st.lastY)
                st.lastFrameDistPx = distPx
                st.totalDistPx += distPx
                st.speedMmPerSec = if (dtMs > 0) {
                    capabilities.dimFromPx(distPx) / (dtMs / 1000f)
                } else 0f
                st.lastX = c.contact.x
                st.lastY = c.contact.y
                st.lastTimeNanos = nowNanos
                if (distPx > pxPerMm * MOVEMENT_JITTER_MM) {
                    st.lastMoveTimeNanos = nowNanos
                }
                val size = c.contact.maxDimMm
                st.smoothedContactSizeMm =
                    st.smoothedContactSizeMm * (1f - SIZE_SMOOTH_FACTOR) + size * SIZE_SMOOTH_FACTOR
                st.lastContactSizeMm = size
                st.rawSampleCount++

                // --- Sliding velocity window (pruned to the configured window, >= 2 samples) ---
                st.recentSamples.addLast(MotionSample(nowNanos, c.contact.x, c.contact.y))
                val windowCutoff = nowNanos - settings.velocityWindowMs * 1_000_000L
                while (st.recentSamples.size > 2 && st.recentSamples.first().timeNanos < windowCutoff) {
                    st.recentSamples.removeFirst()
                }
                val spanMs =
                    (st.recentSamples.last().timeNanos - st.recentSamples.first().timeNanos) / 1_000_000L
                st.windowedVelocityMmPerSec = if (st.recentSamples.size >= 2 && spanMs > 0) {
                    val dx = st.recentSamples.last().x - st.recentSamples.first().x
                    val dy = st.recentSamples.last().y - st.recentSamples.first().y
                    capabilities.dimFromPx(hypot(dx, dy)) / (spanMs / 1000f)
                } else 0f
                st.movingSampleCount = 0
                for (i in 1 until st.recentSamples.size) {
                    val a = st.recentSamples[i - 1]
                    val b = st.recentSamples[i]
                    if (hypot(b.x - a.x, b.y - a.y) > pxPerMm * MOVEMENT_JITTER_MM) {
                        st.movingSampleCount++
                    }
                }
            }
        }
        pointerStates.keys.retainAll(presentIds)

        // --- 2. Resting context: edge proximity, clusters, multi-contact evidence. ------
        val widthPx = if (viewportWidthPx > 0f) viewportWidthPx else capabilities.displayMaxPx
        val heightPx = if (viewportHeightPx > 0f) viewportHeightPx else capabilities.displayMaxPx
        val edgePx = settings.edgeMarginMm * pxPerMm
        val totalContacts = baseClassified.size

        fun nearEdge(c: NormalizedContact): Boolean {
            val x = c.x
            val y = c.y
            return x <= edgePx || y <= edgePx || x >= widthPx - edgePx || y >= heightPx - edgePx
        }

        fun stationaryMs(st: PointerMotionState): Long =
            ((nowNanos - st.lastMoveTimeNanos) / 1_000_000L).coerceAtLeast(0L)

        /** The adaptive stroke gate: resting-hand noise forces a higher minimum velocity. */
        fun effectivePromoteVelocity(): Float =
            max(settings.minPromoteVelocityMmPerSec, restingNoiseMmPerSec * PROMOTE_VS_NOISE)

        /**
         * "Stroke-like motion" = the contact is moving fast enough over the velocity window
         * to be a deliberate stroke, AND it has travelled far enough in total to not be a
         * fresh-down artifact. Velocity-gating means a slow, jittery resting finger never
         * looks like a writer even if it drifts a few mm.
         */
        fun strokeLikeMotion(st: PointerMotionState): Boolean =
            st.windowedVelocityMmPerSec >= effectivePromoteVelocity() &&
                capabilities.dimFromPx(st.totalDistPx) >= settings.movementPromoteThresholdMm

        val restingCount = baseClassified.count {
            pointerStates[it.contact.pointerId]?.restingClassification == ContactClassification.RESTING
        }
        val hasPalmOrResting = restingCount > 0 || baseClassified.any {
            it.classification == ContactClassification.PALM
        }

        // Resting clusters: connected components of contacts within clusterDistancePx.
        val clusterDistancePx = settings.clusterDistanceThresholdMm * pxPerMm
        val parent = HashMap<Int, Int>()
        fun root(p: Int): Int {
            var x = p
            while (parent[x] != x) x = parent[x]!!
            var cur = p
            while (parent[cur] != cur) {
                val next = parent[cur]!!
                parent[cur] = x
                cur = next
            }
            return x
        }
        for (c in baseClassified) parent[c.contact.pointerId] = c.contact.pointerId
        for (i in baseClassified.indices) {
            for (j in i + 1 until baseClassified.size) {
                val a = baseClassified[i].contact
                val b = baseClassified[j].contact
                if (hypot(a.x - b.x, a.y - b.y) <= clusterDistancePx) {
                    val ra = root(a.pointerId)
                    val rb = root(b.pointerId)
                    if (ra != rb) parent[rb] = ra
                }
            }
        }
        val clusterMembers = HashMap<Int, MutableList<Int>>()
        for (c in baseClassified) {
            val r = root(c.contact.pointerId)
            clusterMembers.getOrPut(r) { mutableListOf() }.add(c.contact.pointerId)
        }

        /**
         * True when [pointerId] belongs to a group of >= 2 close contacts that are all
         * (nearly) stationary AND the group sits in a strong resting context. This is how
         * the side of a hand that the digitizer reports as several small contacts is still
         * recognized as a resting hand.
         */
        fun inRestingCluster(pointerId: Int): Boolean {
            val members = clusterMembers[root(pointerId)] ?: return false
            if (members.size < 2) return false
            for (m in members) {
                val st = pointerStates[m] ?: return false
                if (stationaryMs(st) < settings.clusterStationaryThresholdMs) return false
            }
            return totalContacts >= 3 ||
                baseClassified.any { nearEdge(it.contact) } ||
                baseClassified.any { it.classification == ContactClassification.PALM }
        }

        fun restingContext(c: ClassifiedContact): Boolean =
            totalContacts >= 3 ||
                nearEdge(c.contact) ||
                hasPalmOrResting ||
                inRestingCluster(c.contact.pointerId)

        /**
         * True when [pointerId] is part of a cluster of >= 2 contacts where at least two of
         * them moved this frame but NONE has stroke-like velocity. This is the signature of
         * a whole-hand shift (e.g. re-anchoring the palm): the hand moves slowly and
         * coherently, and must not be treated as a writing stroke or a gesture.
         */
        fun inDriftingCluster(pointerId: Int): Boolean {
            val members = clusterMembers[root(pointerId)] ?: return false
            if (members.size < 2) return false
            var movingCount = 0
            for (m in members) {
                val st = pointerStates[m] ?: return false
                if (st.lastFrameDistPx > pxPerMm * MOVEMENT_JITTER_MM) movingCount++
                if (st.windowedVelocityMmPerSec >= effectivePromoteVelocity()) return false
            }
            return movingCount >= 2
        }

        /**
         * Number of contacts the size classifier could NOT confidently reject (non-palm,
         * non-tool). A pen next to a pair of large palms is the only such contact — not
         * ambiguous — so it must write immediately. A resting hand, by contrast, shows up
         * as several small contacts at once, so the presence of two or more ambiguous
         * contacts in a multi-contact frame (or an already-established RESTING finger)
         * means a new small contact is part of the resting hand and must be observed
         * before it can draw.
         */
        val ambiguousSmallCount = baseClassified.count {
            val t = it.contact.toolType
            it.classification != ContactClassification.PALM &&
                it.classification != ContactClassification.REJECTED &&
                t != ToolKind.STYLUS &&
                t != ToolKind.ERASER &&
                it.contact.pointerId != activeWritingPointerId
        }

        fun candidateContext(): Boolean =
            restingCount > 0 || (totalContacts >= 3 && ambiguousSmallCount >= 2)

        fun restingReason(c: ClassifiedContact): ClassificationReason =
            when {
                c.classification == ContactClassification.PALM -> ClassificationReason.LARGE_CONTACT
                inRestingCluster(c.contact.pointerId) -> ClassificationReason.RESTING_CLUSTER
                nearEdge(c.contact) -> ClassificationReason.RESTING_EDGE
                else -> ClassificationReason.RESTING_STATIONARY
            }

        // --- 3. Adjust classifications. -------------------------------------------------
        // Small contacts (excluding locked writer, resting/palm, hardware tools) that have
        // moved like a stroke. Used to tell "one writer among resting fingers" apart from
        // "a multi-finger gesture".
        val movingIds = baseClassified.mapNotNull { c ->
            val st = pointerStates[c.contact.pointerId] ?: return@mapNotNull null
            if (c.classification == ContactClassification.PALM ||
                c.classification == ContactClassification.REJECTED ||
                st.restingClassification == ContactClassification.RESTING ||
                c.contact.pointerId == activeWritingPointerId
            ) {
                null
            } else if (strokeLikeMotion(st)) c.contact.pointerId else null
        }.toSet()

        val adjusted = ArrayList<ClassifiedContact>(baseClassified.size)
        var promoteId: Int? = null
        var cancelId: Int? = null

        for (c in baseClassified) {
            val id = c.contact.pointerId
            val st = pointerStates[id] ?: run {
                adjusted += c
                continue
            }
            val base = c.classification
            val isLockedWriter = id == activeWritingPointerId
            val hardPen = c.contact.toolType == ToolKind.STYLUS
            val isEraser = c.contact.toolType == ToolKind.ERASER

            var finalCls = base
            var reason = c.reason

            when {
                !restingEnabled -> Unit

                hardPen || isEraser -> Unit

                isLockedWriter -> {
                    // A locked writing pointer is sticky: it stays writable (even if the
                    // size classifier momentarily calls it a palm — see
                    // borderlinePenReclassificationNeverDropsLockMidStroke) unless its
                    // SMOOTHED contact size grows into palm territory. Large-growth
                    // cancellation uses smoothing + hysteresis + a significant increase
                    // over the initial size, so a single digitizer spike never cancels an
                    // in-progress stroke.
                    val grew = settings.palmGrowthCancelEnabled &&
                        st.initialContactSizeMm > 0f &&
                        st.smoothedContactSizeMm >= settings.sizeGrowthCancelThresholdMm &&
                        st.smoothedContactSizeMm >= st.initialContactSizeMm * settings.palmGrowthFactor
                    if (grew) {
                        finalCls = ContactClassification.PALM
                        reason = ClassificationReason.PALM_GROWTH_CANCELLED
                        cancelId = id
                    } else {
                        finalCls = ContactClassification.WRITING
                        reason = ClassificationReason.LOCKED_WRITING_POINTER
                    }
                }

                base == ContactClassification.PALM || base == ContactClassification.REJECTED -> Unit

                else -> {
                    when {
                        st.restingClassification == ContactClassification.RESTING -> {
                            // A resting finger can become the writer if it is the ONLY thing
                            // moving while everything else stays put (e.g. the user starts
                            // writing with a finger that was already resting on the screen).
                            if (activeWritingPointerId == null &&
                                movingIds.isEmpty() &&
                                strokeLikeMotion(st) &&
                                st.lastFrameDistPx > pxPerMm * MOVEMENT_JITTER_MM
                            ) {
                                finalCls = ContactClassification.WRITING
                                reason = ClassificationReason.PROMOTED_TO_WRITING
                                promoteId = id
                            } else {
                                finalCls = ContactClassification.RESTING
                                reason = restingReason(c)
                            }
                        }

                        st.isNew -> {
                            // A brand-new contact. In a resting context (or when immediate
                            // drawing is disabled) it is buffered as a CANDIDATE (observed
                            // until it moves); otherwise it keeps its base classification
                            // (isolated touch fast path, or a gesture finger).
                            if (candidateContext() || !settings.allowImmediateDrawWhenIsolated) {
                                finalCls = ContactClassification.CANDIDATE
                                reason = ClassificationReason.CANDIDATE_BUFFER
                            }
                        }

                        st.restingClassification == ContactClassification.CANDIDATE -> {
                            when {
                                strokeLikeMotion(st) -> {
                                    if (activeWritingPointerId == null &&
                                        movingIds.size == 1 &&
                                        movingIds.contains(id)
                                    ) {
                                        // The unique mover among ambiguous contacts: promote it
                                        // to the writing pointer.
                                        finalCls = ContactClassification.WRITING
                                        reason = ClassificationReason.PROMOTED_TO_WRITING
                                        promoteId = id
                                    } else {
                                        // Two or more contacts moving together: a multi-finger
                                        // gesture (pan/zoom), not a stroke.
                                        finalCls = ContactClassification.FINGER
                                    }
                                }
                                stationaryMs(st) >= settings.candidateEvaluationWindowMs -> {
                                    finalCls = ContactClassification.RESTING
                                    reason = restingReason(c)
                                }
                                inDriftingCluster(id) -> {
                                    // Slow coherent whole-hand movement is not a stroke.
                                    finalCls = ContactClassification.RESTING
                                    reason = ClassificationReason.HAND_SHIFT_DRIFT
                                }
                                else -> {
                                    finalCls = ContactClassification.CANDIDATE
                                    reason = ClassificationReason.CANDIDATE_BUFFER
                                }
                            }
                        }

                        else -> {
                            // Previously WRITING/FINGER but an ambiguous multi-contact frame
                            // arrived (resting fingers present or several small contacts):
                            // observe it before drawing or driving gestures, so resting
                            // fingers that were momentarily classified WRITING/FINGER never
                            // draw or pan.
                            if (candidateContext()) {
                                finalCls = ContactClassification.CANDIDATE
                                reason = ClassificationReason.CANDIDATE_BUFFER
                            } else if (restingContext(c) &&
                                stationaryMs(st) >= settings.stationaryRestTimeMs
                            ) {
                                // A stationary contact in a resting context (edge / cluster /
                                // palm present) is a resting finger.
                                finalCls = ContactClassification.RESTING
                                reason = restingReason(c)
                            }
                        }
                    }
                }
            }

            st.restingClassification = finalCls

            val minVel = effectivePromoteVelocity()
            val writeScore = writeScoreFor(st, minVel, settings)
            val restScore = restScoreFor(
                st, settings,
                inRestingCluster(id), nearEdge(c.contact),
            )

            adjusted += ClassifiedContact(
                contact = c.contact,
                classification = finalCls,
                confidence = if (finalCls == base) c.confidence else restingConfidence(finalCls),
                reason = reason,
                effectiveThresholdMm = c.effectiveThresholdMm,
                speedMmPerSec = st.speedMmPerSec,
                durationMs = (nowNanos - st.downTimeNanos) / 1_000_000L,
                downX = st.startX,
                downY = st.startY,
                windowedVelocityMmPerSec = st.windowedVelocityMmPerSec,
                pathLengthMm = capabilities.dimFromPx(st.totalDistPx),
                writeScore = writeScore,
                restScore = restScore,
            )
        }

        // Adaptive resting-noise estimate: EMA of the velocity the resting contacts actually
        // produce, so a hard-resting hand raises the stroke gate and stops getting mistaken
        // for a writer. Decays back toward the configured minimum when nothing is resting.
        var maxNoise = 0f
        for (ac in adjusted) {
            if (ac.classification == ContactClassification.RESTING) {
                val st = pointerStates[ac.contact.pointerId] ?: continue
                maxNoise = max(maxNoise, max(st.windowedVelocityMmPerSec, st.speedMmPerSec))
            }
        }
        restingNoiseMmPerSec =
            restingNoiseMmPerSec * RESTING_NOISE_DECAY + maxNoise * (1f - RESTING_NOISE_DECAY)

        // Cluster bounds for the debug overlay: bounding boxes of groups of >= 2 resting
        // contacts (visualizes where the hand is resting).
        val clusterBounds = ArrayList<ClusterBounds>()
        val restByRoot = HashMap<Int, MutableList<NormalizedContact>>()
        for (ac in adjusted) {
            if (ac.classification == ContactClassification.RESTING) {
                val r = root(ac.contact.pointerId)
                restByRoot.getOrPut(r) { mutableListOf() }.add(ac.contact)
            }
        }
        for (members in restByRoot.values) {
            if (members.size < 2) continue
            clusterBounds += ClusterBounds(
                minX = members.minOf { it.x },
                minY = members.minOf { it.y },
                maxX = members.maxOf { it.x },
                maxY = members.maxOf { it.y },
            )
        }

        // The next frame must see this pointer as established, not "new".
        for (id in presentIds) pointerStates[id]?.isNew = false

        return Result(adjusted, promoteId, cancelId, clusterBounds)
    }

    /** How much the contact looks like a deliberate stroke (0..1), for diagnostics. */
    private fun writeScoreFor(
        st: PointerMotionState,
        minVel: Float,
        settings: PalmRejectionSettings,
    ): Float {
        val velScore = (st.windowedVelocityMmPerSec / minVel).coerceIn(0f, 1f)
        val pathScore =
            (capabilities.dimFromPx(st.totalDistPx) / settings.movementPromoteThresholdMm).coerceIn(0f, 1f)
        val contScore = if (st.recentSamples.size <= 1) 0f
        else st.movingSampleCount.toFloat() / (st.recentSamples.size - 1).toFloat()
        val sizeScore = (1f - st.smoothedContactSizeMm / settings.palmSizeThresholdMm).coerceIn(0f, 1f)
        return velScore * VEL_WEIGHT + pathScore * PATH_WEIGHT +
            contScore * CONT_WEIGHT + sizeScore * SIZE_WEIGHT
    }

    /** How much the contact looks like a resting hand (0..1), for diagnostics. */
    private fun restScoreFor(
        st: PointerMotionState,
        settings: PalmRejectionSettings,
        inCluster: Boolean,
        edgeAdjacent: Boolean,
    ): Float {
        val statScore = if (st.recentSamples.size <= 1) 1f
        else 1f - st.movingSampleCount.toFloat() / (st.recentSamples.size - 1).toFloat()
        val clusterScore = if (inCluster) 1f else 0f
        val edgeScore = if (edgeAdjacent) 1f else 0f
        val sizeScore = (st.smoothedContactSizeMm / settings.palmSizeThresholdMm).coerceIn(0f, 1f)
        val growthRatio =
            if (st.initialContactSizeMm > 0f) st.smoothedContactSizeMm / st.initialContactSizeMm else 0f
        val growthScore = (growthRatio / settings.palmGrowthFactor).coerceIn(0f, 1f)
        return statScore * STAT_WEIGHT + clusterScore * CLUSTER_WEIGHT +
            edgeScore * EDGE_WEIGHT + sizeScore * REST_SIZE_WEIGHT + growthScore * GROWTH_WEIGHT
    }

    private fun restingConfidence(classification: ContactClassification): Float = when (classification) {
        ContactClassification.CANDIDATE -> 0.4f
        ContactClassification.RESTING -> 0.65f
        ContactClassification.FINGER -> 0.5f
        ContactClassification.WRITING -> 0.8f
        ContactClassification.PALM -> 0.8f
        else -> 0.5f
    }

    companion object {
        /** Movement below this (mm) is treated as jitter and does not reset the "stationary" clock. */
        const val MOVEMENT_JITTER_MM = 1.5f

        /** Blend factor for the exponential moving average of contact size. */
        const val SIZE_SMOOTH_FACTOR = 0.3f

        /** Legacy hysteresis multiplier (kept for reference; size-growth now uses its own threshold). */
        const val PALM_GROWTH_HYSTERESIS = 1.15f

        /** Resting-hand noise must be exceeded by this factor before a contact counts as a stroke. */
        const val PROMOTE_VS_NOISE = 3f

        /** EMA decay for the adaptive resting-noise estimate (higher = slower adaptation). */
        const val RESTING_NOISE_DECAY = 0.8f

        // Write-score evidence weights.
        const val VEL_WEIGHT = 0.4f
        const val PATH_WEIGHT = 0.25f
        const val CONT_WEIGHT = 0.2f
        const val SIZE_WEIGHT = 0.15f

        // Rest-score evidence weights.
        const val STAT_WEIGHT = 0.4f
        const val CLUSTER_WEIGHT = 0.2f
        const val EDGE_WEIGHT = 0.15f
        const val REST_SIZE_WEIGHT = 0.15f
        const val GROWTH_WEIGHT = 0.1f
    }
}