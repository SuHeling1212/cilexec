# Snake Game

中文说明：[snake-game.zh-CN.md](snake-game.zh-CN.md)

`cilexec/snake/0.0.3` is a full-screen terminal game written entirely in FCL. It demonstrates
FCL objects, durable process continuations, structured key input, timers, ANSI drawing, and the
post-commit `term.render` frame path.

Install the exact package database SHA-256 shown by the configured market, then import it:

```fcl
market.update()
market.install("<snake-package-sha256>")
import "<snake-package-sha256>" as "snake"
snake.play()
```

The package can also be started through `package.run("<snake-package-sha256>")`.

Controls:

| Key | Action |
| --- | --- |
| Arrow keys or `W/A/S/D` | Turn the snake |
| `P` | Pause or resume |
| `R` | Restart after the game ends |
| `Q` | Quit and return the final score |

The board is sized when a new game starts, up to 40 columns by 20 rows. Resizing the terminal
repaints the current board; if the terminal becomes too small, the game keeps its state and asks
the user to enlarge the window.

The board wraps at every edge. Crossing the left edge enters from the right, crossing the top
enters from the bottom, and the reverse applies to the other two edges. Hitting the snake's own
body still ends the game.

Every completed FCL execution slice persists the `SnakeGame` object before its frame is
published. A Runtime or Docker crash can discard the current repaint and the in-flight movement,
but recovery resumes from the last committed whole game state. The game never persists ANSI
frames themselves because they can be regenerated from that state.
