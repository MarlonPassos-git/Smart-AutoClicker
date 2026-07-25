import test from "node:test";
import assert from "node:assert/strict";

import {
  localizarAssetApk,
  recomendarArquiteturaApk,
  selecionarReleaseComApk,
} from "../assets/seletor-apk.mjs";

test("recomenda arm64-v8a para Android ARM de 64 bits", () => {
  const arquitetura = recomendarArquiteturaApk({
    android: true,
    arquitetura: "arm",
    bits: "64",
  });

  assert.equal(arquitetura, "arm64-v8a");
});

test("recomenda universal quando o navegador não informa a arquitetura", () => {
  const arquitetura = recomendarArquiteturaApk({
    android: true,
    arquitetura: "",
    bits: "",
  });

  assert.equal(arquitetura, "universal");
});

test("diferencia emuladores x86 de 32 e 64 bits", () => {
  assert.equal(
    recomendarArquiteturaApk({ android: true, arquitetura: "x86", bits: "64" }),
    "x86_64",
  );
  assert.equal(
    recomendarArquiteturaApk({ android: true, arquitetura: "x86", bits: "32" }),
    "x86",
  );
});

test("seleciona a primeira release publicada que contém APK", () => {
  const release = selecionarReleaseComApk([
    { draft: true, assets: [{ name: "rascunho.apk" }] },
    { draft: false, tag_name: "4.0.0", assets: [{ name: "Klickr-universal.apk" }] },
  ]);

  assert.equal(release.tag_name, "4.0.0");
});

test("usa o APK universal quando a ABI escolhida não está disponível", () => {
  const asset = localizarAssetApk(
    [
      { name: "Klickr-4.0.0-universal-debug.apk" },
      { name: "Klickr-4.0.0-x86_64-debug.apk" },
    ],
    "arm64-v8a",
  );

  assert.equal(asset.name, "Klickr-4.0.0-universal-debug.apk");
});

