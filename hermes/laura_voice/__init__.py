def register(ctx):
    from .adapter import register as _register
    _register(ctx)


__all__ = ["register"]
