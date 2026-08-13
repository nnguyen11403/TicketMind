"""Domain errors that the HTTP layer knows how to translate.

Anything raised here means *our* upstream failed, Voyage, Anthropic, or a
reply we could not parse. None of it is the caller's fault, so it surfaces as
502 rather than 500: the backend already treats 5xx from us as a degraded
triage and keeps the ticket, but a 502 says "retry may help" where a 500 says
"we crashed".
"""

from __future__ import annotations


class UpstreamError(RuntimeError):
    """A provider we depend on failed or answered unusably."""
