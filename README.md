# 🔥 BlazeFX

Live forex **Breakout Failure (BOF) / liquidity sweep** scanner for Android.

**Download:** https://github.com/renaldibosco/blazefx/releases/latest/download/BlazeFX.apk

## Markets (14)
- **Majors:** EURUSD, GBPUSD, USDJPY, AUDUSD, USDCAD, USDCHF, NZDUSD
- **Crosses:** EURJPY, GBPJPY, USDINR
- **Metals and crypto:** XAUUSD, XAGUSD, BTCUSD, ETHUSD

## Features
- TradingView-style candle chart (Lightweight Charts™) on 5m / 15m / 1h
- Levels: **PDH/PDL**, **PWH/PWL** (previous week), **Asian range ASH/ASL**, Camarilla H3/H4/L3/L4
- **Session bar** for Sydney, Tokyo, London and New York, with 🔥 London and NY **killzones**
- A BOF is detected when price sweeps a level and closes back on the other side. It is scored out of 6:
  1. Higher timeframe rejected the level
  2. Weak sweep (less than 0.5 ATR past the level)
  3. RSI divergence or extreme
  4. Rejection candle
  5. Killzone timing
  6. Major level (PW / PD / Asian / H4-L4)
- Entry, SL and TP 1:2 in **pips**, plus the **lot size** for your balance and risk %
- Phone alerts every minute, with an option for **killzones only**

Data comes from Yahoo Finance for free and can lag. This is a data tool, not investment advice. Built by Pablo Reinaldez.
