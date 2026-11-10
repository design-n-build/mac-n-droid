"""Small preferences file next to identity.json."""
import json

from .identity import config_dir

DEFAULTS = {
    "mirror_notifications": True,
    "notification_sound": True,
    "sync_clipboard": True,
    "open_at_login": False,
    "last_phone_name": "",
}


class Settings:
    def __init__(self):
        self.path = config_dir() / "settings.json"
        try:
            self.values = {**DEFAULTS, **json.loads(self.path.read_text())}
        except (OSError, ValueError):
            self.values = dict(DEFAULTS)

    def __getitem__(self, key):
        return self.values.get(key, DEFAULTS.get(key))

    def __setitem__(self, key, value):
        self.values[key] = value
        try:
            self.path.write_text(json.dumps(self.values, indent=2))
        except OSError:
            pass
