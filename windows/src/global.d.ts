import type { YySlideApi } from "./types";

declare global {
  interface Window {
    yySlide: YySlideApi;
  }
}

export {};
