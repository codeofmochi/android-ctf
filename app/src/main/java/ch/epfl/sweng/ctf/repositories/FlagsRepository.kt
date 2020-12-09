package ch.epfl.sweng.ctf.repositories

import android.content.Context
import ch.epfl.sweng.ctf.models.Challenge
import ch.epfl.sweng.ctf.models.RankedUser
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import io.michaelrocks.paranoid.Obfuscate
import io.paperdb.Paper
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.CompletableFuture


/**
 * Data class to model persisted current user
 *
 * @param uuid Unique user identifier
 * @param name Saved username
 */
class User(val uuid: String, name: String) {
    val name: String = generateName(name)

    private fun generateName(name: String) = "$name #${uuid.takeLast(4).toUpperCase()}"

    fun withName(name: String) = User(uuid, name)
}

/**
 * Flags repository
 * Provides flags checking and distribution for challenges
 *
 * WARNING: THIS FILE IS PURPOSELY GIT IGNORED IN PUBLIC REPOS, AS IT CONTAINS THE SOLUTIONS.
 * THE PROJECT WILL NOT COMPILE WITHOUT THIS FILE
 *
 * CTF organizers: modify these flags according to your needs
 *
 * @author Alexandre Chau
 */
interface FlagsRepository {
    /**
     * Provides the flag shape with '%s' as placeholder
     *
     * @return Flag template string with '%s' as secret value placeholder
     */
    fun getFlagTemplate(): String

    /**
     * Returns the flag value for a given challenge name
     *
     * @return Flag value
     */
    fun getFlag(challengeName: String): String

    /**
     * Check a flag value for a given challenge name
     *
     * @return Flag validity
     */
    fun checkFlag(challengeName: String, value: String): Boolean

    /**
     * Returns a list of challenges combined with the persisted state
     */
    fun combineWithPersistedState(challengesData: List<Challenge>): List<Challenge>

    /**
     * Deletes all persisted data
     */
    fun deleteAllPersistedData()

    /**
     * Retrieves the leaderboard
     */
    fun getLeaderboard(): CompletableFuture<List<RankedUser>>

    /**
     * Uploads solved flags that have not been saved to cloud yet
     */
    fun uploadMissing(
        challengesRepository: ChallengesRepository,
        force: Boolean = false
    ): CompletableFuture<Boolean>?

    /**
     * Saves the current user to disk and cloud
     */
    fun saveCurrentUser(newUser: User)

    /**
     * Returns the current user of the app
     */
    fun getCurrentUser(): User
}


object PlainFlags {
    /**
     * Flag for the "gnireenignE" challenge
     * Purposely not obfuscated, so that it can be found by reverse engineering
     */
    val gnireenignEFlag = "flag{Gaifa4jewa4phoy}"
}


/**
 * Secret implementation of FlagsRepository
 *
 * WARNING: THIS FILE IS PURPOSELY GIT IGNORED IN PUBLIC REPOS, AS IT CONTAINS THE SOLUTIONS.
 * THE PROJECT WILL NOT COMPILE WITHOUT THIS FILE
 *
 * Strings in this class must be obfuscated using the @Obfuscate annotation from
 * [io.michaelrocks.paranoid.Obfuscate]
 */
@Obfuscate
class FlagsRepositoryImpl(ctx: Context) : FlagsRepository {
    init {
        Paper.init(ctx)
    }

    override fun getFlagTemplate(): String = "flag{%s}"

    // in-memory cache of persisted state
    private var persistedCache: MutableMap<String, PersistedChallengeState> = loadFromPersistence()

    /**
     * Dirty implementation that uses Challenge name to identify it
     * (cause: instantiation at [ch.epfl.sweng.ctf.repositories.ChallengesRepositoryImpl.challenges])
     *
     * Obfuscated trough class-level @Obfuscate annotation
     */
    private val namesToFlags: Map<String, String> = mapOf(
        "Intro" to getFlagTemplate().replace("%s", "ahxohrahp7Iy7uu"),
        "\"This will be removed in prod\"" to getFlagTemplate().replace("%s", "hoNg3lee4Eiquoo"),
        "Hidden in plain s(d)ight" to getFlagTemplate().replace("%s", "Ilaatee9ooGai3i"),
        "Early bird" to getFlagTemplate().replace("%s", "kah4iexahB2Ixae"),
        "Fruity injection" to getFlagTemplate().replace("%s", "iebi5Maequee7mi"),
        "Crossy web" to getFlagTemplate().replace("%s", "yat6achongooFih"),
        "The debugger is overrated" to getFlagTemplate().replace("%s", "vei6xe5Ij9ateig"),
        "On the wire" to getFlagTemplate().replace("%s", "Ohx3quaojaSahsh"),
        "Java++" to getFlagTemplate().replace("%s", "sha1Iepood1ohb4"),
        "gnireenignE" to PlainFlags.gnireenignEFlag,
    )

    override fun getFlag(challengeName: String): String {
        return namesToFlags[challengeName] ?: "ERROR: challenge not found"
    }

    private fun saveFlagsState() {
        // save changes to disk asynchronously
        GlobalScope.launch {
            Paper.book(PAPER_DB_CHALLENGES_BOOK)
                .write(PAPER_DB_CHALLENGES_STATE_INDEX, persistedCache)
        }
    }

    override fun checkFlag(challengeName: String, value: String): Boolean {
        val maybeFlag = namesToFlags[challengeName]
        return when {
            maybeFlag == null -> false
            maybeFlag != value -> false
            else -> {
                // flag is correct
                // update persistence cache
                persistedCache[challengeName] = PersistedChallengeState(
                    solved = true,
                    uploaded = false,
                    flagValue = value,
                )
                saveFlagsState()
                true
            }
        }
    }

    override fun combineWithPersistedState(challengesData: List<Challenge>): List<Challenge> {
        return challengesData.map { challenge ->
            // check if challenge is in cache, otherwise return same object (no changes to be made)
            val maybeState = persistedCache[challenge.name]
            if (maybeState == null) challenge
            else {
                // otherwise, check if state indicates that challenge is solved
                if (maybeState.solved) challenge.withStatus(Challenge.Status.SOLVED)
                else challenge
            }
        }
    }

    override fun deleteAllPersistedData() {
        // reset cache(s)
        persistedCache = mutableMapOf()
        // wipe disk data
        Paper.book(PAPER_DB_CHALLENGES_BOOK).destroy()
    }

    private fun loadFromPersistence(): MutableMap<String, PersistedChallengeState> {
        // load persisted data from disk SYNCHRONOUSLY
        // (OK, because only once at instantiation. Then changes must be written to cache first)
        return Paper.book(PAPER_DB_CHALLENGES_BOOK)
            .read(PAPER_DB_CHALLENGES_STATE_INDEX, mutableMapOf())
    }


    /**
     * Firestore implementation
     */
    private val db = Firebase.firestore
    private var user: User = loadUserFromPersistence()

    override fun getLeaderboard(): CompletableFuture<List<RankedUser>> {
        val future = CompletableFuture<List<RankedUser>>()
        db.collection(FIREBASE_USERS_COLLECTION)
            .get()
            .addOnSuccessListener { documents ->
                val rankedUsers =
                    documents.mapNotNull { document ->
                        val raw = document.data
                        val maybeName = raw["name"] as? String
                        val maybePoints = raw["points"] as? Long
                        if (maybeName == null || maybePoints == null) null
                        else RankedUser(maybeName, maybePoints.toInt())
                    }.sortedByDescending { user -> user.points }
                future.complete(rankedUsers)
            }
            .addOnFailureListener { e -> future.completeExceptionally(e) }
        return future
    }

    override fun uploadMissing(
        challengesRepository: ChallengesRepository,
        force: Boolean,
    ): CompletableFuture<Boolean>? {
        val missing = persistedCache.filterValues { s -> s.solved && !s.uploaded }

        return if (!force && missing.isEmpty()) null
        else {
            val future = CompletableFuture<Boolean>()
            val value = hashMapOf(
                "name" to user.name,
                "points" to challengesRepository.getCurrentScore(),
            )
            db.collection(FIREBASE_USERS_COLLECTION)
                .document(user.uuid)
                .set(value)
                .addOnSuccessListener {
                    missing.forEach { (name, state) ->
                        persistedCache[name] = state.withUploaded(true)
                    }
                    saveFlagsState()
                    future.complete(true)
                }
                .addOnFailureListener { e -> future.completeExceptionally(e) }
            future
        }
    }

    override fun getCurrentUser(): User {
        return user
    }

    override fun saveCurrentUser(newUser: User) {
        user = newUser
        GlobalScope.launch {
            Paper.book(PAPER_DB_USER_BOOK).write(PAPER_DB_USER_CURRENT_INDEX, user)
        }
    }

    /**
     * Attempts to load a user from persistence, otherwise
     * creates a new one and save it to disk
     */
    private fun loadUserFromPersistence(): User {
        fun createAndSaveUser(): User {
            val uuid = UUID.randomUUID().toString()
            val user = User(uuid, "SwEng Enthusiast")
            saveCurrentUser(user)
            return user
        }

        val maybeUser = Paper.book(PAPER_DB_USER_BOOK).read<User?>(PAPER_DB_USER_CURRENT_INDEX)
        return maybeUser ?: createAndSaveUser()
    }

    companion object {
        // Disk-local filenames for PaperDB
        private const val PAPER_DB_CHALLENGES_BOOK = "challenges"
        private const val PAPER_DB_CHALLENGES_STATE_INDEX = "state"

        private const val PAPER_DB_USER_BOOK = "user"
        private const val PAPER_DB_USER_CURRENT_INDEX = "current"

        // Firebase indices
        private const val FIREBASE_USERS_COLLECTION = "users"
    }

    /**
     * Data class to model persisted state per challenge
     *
     * @param solved Whether the challenge was solved (1-to-1 mapping with [ch.epfl.sweng.ctf.models.Challenge.Status])
     * @param uploaded Whether this challenge, when solved, has been already uploaded to the online leaderboard
     * @param flagValue Flag value saved when user solved the challenge
     */
    data class PersistedChallengeState(
        val solved: Boolean,
        val uploaded: Boolean,
        val flagValue: String,
    ) {
        fun withUploaded(uploaded: Boolean) = PersistedChallengeState(solved, uploaded, flagValue)
    }
}