# encoding: utf-8
# JoiPlay RGSS postload adapter. Never reads pixels or modifies game data/saves.
module LunaJoiText
  VERSION = 'LUNA_JOI_1'
  GAME_ID = '__LUNA_GAME_ID__'
  FALLBACK_TITLE = '__LUNA_GAME_TITLE__'
  ENGINE = '__LUNA_ENGINE__'
  PATH = 'LunaGameText.txt'
  SESSION = "#{Time.now.to_f}-#{object_id}"
  @sequence = 0
  @last_beat = 0
  def self.write(kind, text)
    return if text.to_s.empty? || text.to_s.unpack('C*').size > 16384
    @sequence += 1
    mode = File.exist?(PATH) && File.size(PATH) < 131072 ? 'ab' : 'wb'
    File.open(PATH, mode) {|f| f.write("#{SESSION}\t#{@sequence}\t#{kind}\t#{text.to_s.unpack('H*')[0]}\n") }
  rescue StandardError
    # A translation/storage failure must never stop the game.
  end
  def self.pulse
    now = (Time.now.to_f * 1000).to_i
    visible = (defined?($game_message) && $game_message && $game_message.respond_to?(:busy?) && $game_message.busy?) ||
      (defined?($game_temp) && $game_temp && $game_temp.respond_to?(:message_text) && !$game_temp.message_text.to_s.empty?) ||
      (defined?(SceneManager) && defined?(Scene_Battle) && SceneManager.scene_is?(Scene_Battle)) ||
      (defined?($scene) && defined?(Scene_Battle) && $scene.is_a?(Scene_Battle))
    return if now - @last_beat < 1000 && @last_visible == !!visible
    @last_beat = now
    @last_visible = !!visible
    title = defined?($data_system) && $data_system && $data_system.respond_to?(:game_title) ? $data_system.game_title : FALLBACK_TITLE
    title = FALLBACK_TITLE if title.to_s.strip.empty?
    write('luna_game', [VERSION, GAME_ID, ENGINE, title.to_s.gsub(/[\r\n\t]/, ' '), now.to_s, visible ? '1' : '0'].join("\n"))
  rescue StandardError
  end
  def self.emit(kind, text)
    pulse
    value = text.to_s.gsub(/\e[A-Za-z]+(?:\[[^\]]*\])?|\e[^\s]?/, '').strip
    write(kind, value)
  rescue StandardError
  end
end

if defined?(LunaGameText) && LunaGameText.respond_to?(:emit)
  # Older Luna-owned game adapters already wrap the real message methods.
  # Route their callbacks into this identified stream without a second hook.
  class << LunaGameText
    def emit(kind, text); LunaJoiText.emit(kind, text); end
  end
else
  # XP/VX use a different message lifecycle. Observe the original window's
  # update without replacing its rendering, event, choice or battle logic.
  if LunaJoiText::ENGINE != 'RGSS3' && defined?(Window_Message) && Window_Message.method_defined?(:update)
    class Window_Message
      unless method_defined?(:luna_joi_legacy_update)
        alias luna_joi_legacy_update update
        def update
          begin
          text = if defined?($game_message) && $game_message && $game_message.respond_to?(:texts)
            choices = $game_message.respond_to?(:choices) ? $game_message.choices : []
            ($game_message.texts + choices).join("\n")
          elsif defined?($game_temp) && $game_temp && $game_temp.respond_to?(:message_text)
            $game_temp.message_text.to_s
          else '' end
          if text.empty?
            @luna_joi_last_page = nil
          elsif text != @luna_joi_last_page
            @luna_joi_last_page = text.dup
            text = text.gsub(/\\[Vv]\[([0-9]+)\]/) { defined?($game_variables) ? $game_variables[$1.to_i].to_s : $& }
            text = text.gsub(/\\[Nn]\[([0-9]+)\]/) { defined?($game_actors) && $game_actors[$1.to_i] ? $game_actors[$1.to_i].name : $& }
            text = text.gsub(/\\[Cc]\[[0-9]+\]|\\[.!|^><{}]/, '')
            LunaJoiText.emit('dialogue', text)
          end
          rescue StandardError
            # Capture failures must not interrupt the original message loop.
          end
          luna_joi_legacy_update
        end
      end
    end
  end
  if defined?(Window_Message) && Window_Message.method_defined?(:process_all_text)
    class Window_Message
      unless method_defined?(:luna_joi_process_all_text)
        alias luna_joi_process_all_text process_all_text
        def process_all_text
          kind = defined?(SceneManager) && defined?(Scene_Battle) && SceneManager.scene_is?(Scene_Battle) ? 'battle_message' : 'dialogue'
          page = [$game_message.all_text] + $game_message.choices
          LunaJoiText.emit(kind, convert_escape_characters(page.join("\n")))
          luna_joi_process_all_text
        end
      end
    end
  end
  if defined?(Window_ChoiceList) && Window_ChoiceList.method_defined?(:start)
    class Window_ChoiceList
      unless method_defined?(:luna_joi_choice_start)
        alias luna_joi_choice_start start
        def start
          LunaJoiText.emit('choices', convert_escape_characters($game_message.choices.join("\n"))) unless $game_message.has_text?
          luna_joi_choice_start
        end
      end
    end
  end
  if defined?(Window_BattleLog) && Window_BattleLog.method_defined?(:add_text)
    class Window_BattleLog
      unless method_defined?(:luna_joi_add_text)
        alias luna_joi_add_text add_text
        def add_text(text)
          LunaJoiText.emit('battle_log', convert_escape_characters(text))
          luna_joi_add_text(text)
        end
      end
    end
  end
end

class << Graphics
  unless method_defined?(:luna_joi_update)
    alias luna_joi_update update
    def update
      result = luna_joi_update
      LunaJoiText.pulse
      result
    end
  end
end
LunaJoiText.pulse
