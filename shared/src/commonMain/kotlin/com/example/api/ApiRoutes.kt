package com.example.api

/**
 * Every path this API answers on, written down once so the server and the app cannot drift apart.
 *
 * Paths are absolute and already carry the version prefix, so a client never assembles one by
 * hand and the server never nests a literal segment under a literal parent. A route that takes a
 * path parameter is published twice: [Users.BY_ID] is the template the server registers, and
 * [Users.byId] is the filled path a client calls.
 */
object ApiRoutes {
    /** A breaking change means a new prefix here, never a silent change to this one. */
    const val API_PREFIX = "/api/v1"

    object Auth {
        const val PATH = "$API_PREFIX/auth"

        const val REGISTER = "$PATH/register"
        const val LOGIN = "$PATH/login"
        const val SOCIAL = "$PATH/social"
        const val REFRESH = "$PATH/refresh"
        const val PASSWORD = "$PATH/password"
        const val LOGOUT = "$PATH/logout"
        const val LOGOUT_ALL = "$PATH/logout-all"
        const val LINK = "$PATH/link"
    }

    object Users {
        const val PATH = "$API_PREFIX/users"

        const val ME = "$PATH/me"

        /** Multipart upload on `POST`, removal on `DELETE`. Always acts on the token's subject. */
        const val ME_AVATAR = "$ME/avatar"

        /** The name of the path parameter in [BY_ID], so a server never spells it twice. */
        const val USER_ID = "userId"

        /** Registration template. Use [byId] to build the path a client actually calls. */
        const val BY_ID = "$PATH/{$USER_ID}"

        fun byId(userId: String): String = "$PATH/$userId"
    }

    /**
     * Stored images, uploaded through [Users.ME_AVATAR] and served back from here.
     *
     * Unlike everything else under this object these paths need no access token: the id is a
     * random UUID and standing in for the credential is its whole job, so an image loader can
     * fetch one the way it fetches any other URL.
     */
    object Images {
        const val PATH = "$API_PREFIX/images"

        /** The name of the path parameter in [BY_ID], so a server never spells it twice. */
        const val IMAGE_ID = "imageId"

        /** Registration template. Use [byId] to build the path a client actually calls. */
        const val BY_ID = "$PATH/{$IMAGE_ID}"

        fun byId(imageId: String): String = "$PATH/$imageId"
    }
}
