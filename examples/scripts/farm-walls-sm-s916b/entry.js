import { parameters } from "klickr";
import { runFarm } from "./farm.js";

await runFarm(await parameters());
