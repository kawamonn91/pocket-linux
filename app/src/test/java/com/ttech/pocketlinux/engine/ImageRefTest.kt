package com.ttech.pocketlinux.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageRefTest {

    @Test
    fun parse_DockerHub公式イメージ() {
        val ubuntu = ImageRef.parse("ubuntu:24.04")
        assertEquals(ImageRef.DOCKER_HUB_HOST, ubuntu.registry)
        assertEquals("library/ubuntu", ubuntu.repository)
        assertEquals("24.04", ubuntu.tag)
        assertNull(ubuntu.digest)
        assertEquals("24.04", ubuntu.reference)
        assertEquals("ubuntu:24.04", ubuntu.displayName)

        val alpine = ImageRef.parse("alpine")
        assertEquals(ImageRef.DOCKER_HUB_HOST, alpine.registry)
        assertEquals("library/alpine", alpine.repository)
        assertEquals("latest", alpine.tag)
        assertNull(alpine.digest)
        assertEquals("latest", alpine.reference)
        assertEquals("alpine:latest", alpine.displayName)
    }

    @Test
    fun parse_DockerHubユーザーリポジトリ() {
        val custom = ImageRef.parse("myuser/myimage:v1")
        assertEquals(ImageRef.DOCKER_HUB_HOST, custom.registry)
        assertEquals("myuser/myimage", custom.repository)
        assertEquals("v1", custom.tag)
        assertNull(custom.digest)
        assertEquals("myuser/myimage:v1", custom.displayName)

        val explicitHost = ImageRef.parse("docker.io/myuser/myimage:v2")
        assertEquals(ImageRef.DOCKER_HUB_HOST, explicitHost.registry)
        assertEquals("myuser/myimage", explicitHost.repository)
        assertEquals("v2", explicitHost.tag)
        assertEquals("myuser/myimage:v2", explicitHost.displayName)
    }

    @Test
    fun parse_カスタムレジストリ() {
        val ghcr = ImageRef.parse("ghcr.io/owner/repo:v1")
        assertEquals("ghcr.io", ghcr.registry)
        assertEquals("owner/repo", ghcr.repository)
        assertEquals("v1", ghcr.tag)
        assertNull(ghcr.digest)
        assertEquals("ghcr.io/owner/repo:v1", ghcr.displayName)

        val local = ImageRef.parse("localhost:5000/myimage:latest")
        assertEquals("localhost:5000", local.registry)
        assertEquals("myimage", local.repository)
        assertEquals("localhost:5000/myimage:latest", local.displayName)
    }

    @Test
    fun parse_ダイジェスト指定() {
        val digest = "sha256:" + "a".repeat(64)
        val python = ImageRef.parse("python@$digest")
        assertEquals(ImageRef.DOCKER_HUB_HOST, python.registry)
        assertEquals("library/python", python.repository)
        assertNull(python.tag)
        assertEquals(digest, python.digest)
        assertEquals(digest, python.reference)
        assertEquals("python@$digest", python.displayName)
    }

    @Test
    fun parse_不正なイメージ名は例外を投げる() {
        assertThrows(IllegalArgumentException::class.java) { ImageRef.parse("") }
        assertThrows(IllegalArgumentException::class.java) { ImageRef.parse("   ") }
        assertThrows(IllegalArgumentException::class.java) { ImageRef.parse("ubuntu 24.04") }
        assertThrows(IllegalArgumentException::class.java) { ImageRef.parse("ubuntu@sha256:short") }
        assertThrows(IllegalArgumentException::class.java) { ImageRef.parse("ubuntu@invalid") }
        assertThrows(IllegalArgumentException::class.java) { ImageRef.parse("ubuntu:invalid tag") }
        assertThrows(IllegalArgumentException::class.java) { ImageRef.parse("ubuntu//24.04") }
    }
}
