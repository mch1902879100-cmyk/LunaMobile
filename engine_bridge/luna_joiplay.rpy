# Ren'Py source-string adapter. Uses engine callbacks/screens; never captures pixels.
init 999 python hide:
    import os
    import time
    import binascii
    import re

    class LunaJoiRenpy(object):
        def __init__(self):
            self.game_id = '__LUNA_GAME_ID__'
            self.title = '__LUNA_GAME_TITLE__'
            self.session = str(time.time()) + '-' + str(id(self))
            self.sequence = 0
            self.previous = None
            self.frames = []
            self.last_beat = 0
            self.path = os.path.join(config.gamedir, 'LunaGameText.txt')

        def text(self, value):
            try:
                value = unicode(value)
            except NameError:
                value = str(value)
            return re.sub(r'\{[^{}]*\}', '', value).strip()

        def screen_value(self, screen, name):
            if not renpy.get_screen(screen):
                return None
            try:
                return renpy.get_screen_variable(name, screen=screen)
            except Exception:
                try:
                    getter = getattr(renpy, 'get_displayable', None) or renpy.get_widget
                    widget = getter(screen, name)
                    return getattr(widget, 'text', None)
                except Exception:
                    return None

        def row(self, kind, text):
            data = text.encode('utf-8')
            if not data or len(data) > 16384:
                return None
            self.sequence += 1
            encoded = binascii.hexlify(data)
            if not isinstance(encoded, str):
                encoded = encoded.decode('ascii')
            return '%s\t%s\t%s\t%s\n' % (self.session, self.sequence, kind, encoded)

        def pulse(self):
            try:
                parts = []
                who = self.screen_value('say', 'who')
                what = self.screen_value('say', 'what')
                if who:
                    parts.append(self.text(who))
                if what:
                    parts.append(self.text(what))
                nvl = self.screen_value('nvl', 'dialogue')
                if nvl:
                    for entry in nvl:
                        if getattr(entry, 'who', None):
                            parts.append(self.text(entry.who))
                        if getattr(entry, 'what', None):
                            parts.append(self.text(entry.what))
                items = self.screen_value('choice', 'items')
                if items:
                    for item in items:
                        caption = getattr(item, 'caption', None)
                        if caption:
                            parts.append(self.text(caption))
                text = '\n'.join(p for p in parts if p)
                visible = bool(text)
                now = time.time()
                if text != self.previous:
                    self.frames = []
                    if text:
                        value = self.row('choices' if items else 'dialogue', text)
                        if value:
                            self.frames.append(value)
                    self.previous = text
                elif now - self.last_beat < 1:
                    return
                self.last_beat = now
                title = self.text(getattr(config, 'name', None) or self.title).replace('\n', ' ').replace('\t', ' ')[:120]
                meta = self.row('luna_game', '\n'.join(['LUNA_JOI_1', self.game_id, 'RENPY', title, str(int(now * 1000)), '1' if visible else '0']))
                with open(self.path, 'wb') as stream:
                    stream.write((''.join(self.frames) + meta).encode('utf-8'))
            except Exception:
                # An adapter/storage error must never break the game's interaction.
                pass

    adapter = LunaJoiRenpy()
    config.interact_callbacks.append(adapter.pulse)
    if hasattr(config, 'periodic_callbacks'):
        config.periodic_callbacks.append(adapter.pulse)
    elif hasattr(config, 'periodic_callback'):
        previous_periodic = config.periodic_callback
        def periodic():
            if previous_periodic:
                previous_periodic()
            adapter.pulse()
        config.periodic_callback = periodic
