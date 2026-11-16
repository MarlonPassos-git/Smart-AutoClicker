import { parameters } from "klickr";
import { runRound } from "./round.js";

const selected = await parameters();
const options = { ...selected, multivillage: selected.villages.length > 1, goldMinimum: 0, elixirMinimum: 0 };
await runRound(options);
