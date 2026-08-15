"""Column-level encryption for knowledge-base documents.

The knowledge base is a mirror of resolved tickets, so it holds the same
user-authored text the backend encrypts in ``tickets.description``. Storing it
in the clear here would make the backend's encryption decorative: an attacker
who can read ``kb_documents`` would have the interesting half of the ticket
history anyway.

The envelope is byte-for-byte the one ``FieldCipher`` writes on the Java side
(``v1:<b64 iv>:<b64 ciphertext||tag>``, AES-256-GCM, 12-byte IV, 128-bit tag)
under the same ``APP_ENCRYPTION_KEY``. One key, one format, either service can
read what the other wrote.

Embeddings are computed over plaintext before it is encrypted — ciphertext has
no semantics to embed. The vector is therefore the one column here that still
says something about the content, which is inherent to searchable encryption
being a different problem than this one.
"""

from __future__ import annotations

import base64
import os

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

_PREFIX_V1 = "v1:"
_IV_LENGTH = 12
_KEY_LENGTH = 32


class FieldCipher:
    """AES-256-GCM over individual column values."""

    def __init__(self, key: bytes) -> None:
        if len(key) != _KEY_LENGTH:
            raise ValueError(f"encryption key must be exactly {_KEY_LENGTH} bytes, got {len(key)}")
        self._aead = AESGCM(key)

    @classmethod
    def from_base64(cls, encoded: str) -> FieldCipher:
        if not encoded or not encoded.strip():
            raise ValueError(
                "APP_ENCRYPTION_KEY is not set. Generate one with: openssl rand -base64 32"
            )
        try:
            key = base64.b64decode(encoded.strip(), validate=True)
        except Exception as exc:
            raise ValueError("APP_ENCRYPTION_KEY must be base64-encoded") from exc
        return cls(key)

    def encrypt(self, plaintext: str | None) -> str | None:
        if plaintext is None:
            return None
        iv = os.urandom(_IV_LENGTH)
        ciphertext = self._aead.encrypt(iv, plaintext.encode("utf-8"), None)
        return (
            _PREFIX_V1
            + base64.b64encode(iv).decode("ascii")
            + ":"
            + base64.b64encode(ciphertext).decode("ascii")
        )

    def decrypt(self, stored: str | None) -> str | None:
        """Decrypt a stored value, passing unprefixed values through.

        The passthrough is the read half of encrypt-on-write: rows written
        before this module existed stay readable and are re-encrypted the next
        time the backend re-posts the ticket. Writes always encrypt.
        """

        if stored is None or not stored.startswith(_PREFIX_V1):
            return stored
        parts = stored.split(":", 2)
        if len(parts) != 3:
            raise ValueError("malformed encrypted value: expected v1:<iv>:<ciphertext>")
        try:
            iv = base64.b64decode(parts[1], validate=True)
            ciphertext = base64.b64decode(parts[2], validate=True)
            return self._aead.decrypt(iv, ciphertext, None).decode("utf-8")
        except (InvalidTag, ValueError) as exc:
            # Wrong key or a row edited underneath us. Both have to be loud:
            # handing the raw ciphertext back would put it in a Claude prompt
            # as though it were the document.
            raise ValueError("field decryption failed") from exc
