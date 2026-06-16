/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.BaritoneAPI;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class HuntCommand extends Command {

    public HuntCommand(IBaritone baritone) {
        super(baritone, "hunt");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        if (args.hasAny()) {
            String arg = args.getString().toLowerCase();
            if (arg.equals("stop") || arg.equals("off") || arg.equals("cancel")) {
                baritone.getHuntProcess().cancel();
                logDirect("Hunt stopped");
                return;
            }
            if (!arg.equals("start") && !arg.equals("on")) {
                logDirect("Unknown argument. Usage: hunt [start|stop]");
                return;
            }
        }
        baritone.getHuntProcess().hunt();
        Settings s = BaritoneAPI.getSettings();
        logDirect(String.format(
                "Hunting targets named \"%s\"%s with texture %s. Auto-attack: %s.",
                s.huntName.value,
                s.huntNameColorAqua.value ? " (aqua)" : "",
                s.huntTextureHash.value,
                s.huntAutoAttack.value ? "on" : "off"
        ));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            String prefix = args.getString().toLowerCase();
            return Stream.of("start", "stop").filter(s -> s.startsWith(prefix));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Walk to (and hit) the Hunt player-heads";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The hunt command tells Baritone to path toward every nearby \"Hunt\" player-head and,",
                "if huntAutoAttack is enabled, hit them automatically once in reach.",
                "",
                "Targets are matched by the head texture (setting huntTextureHash) or by an aqua name",
                "(settings huntName / huntNameColorAqua). Tune behaviour with the hunt* settings.",
                "",
                "Usage:",
                "> hunt       - start hunting",
                "> hunt start - start hunting",
                "> hunt stop  - stop hunting"
        );
    }
}
