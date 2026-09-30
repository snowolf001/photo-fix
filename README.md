# Photo Fix

A deliberately small Android prototype for validating one question:

> Can a fully on-device, one-tap enhancement pipeline make ordinary phone photos consistently look better without generative AI?

## Prototype scope

- Pick a photo from the device.
- Analyze brightness and tonal distribution locally.
- Apply scene-adaptive exposure, shadows/highlights, mild white balance, vibrance and sharpening.
- Toggle Before / After.
- Save the enhanced photo.
- No account.
- No backend.
- No upload.
- No AI API cost.

This is **not** yet a production photo editor. The pipeline is intentionally conservative so that validation can focus on whether the output is naturally better rather than merely more dramatic.

## Validation plan

Test roughly 20–30 real photos covering:

- dark indoor photos
- backlit people
- cloudy outdoor scenes
- landscapes
- food
- portraits
- night shots
- warm/cool color casts
- slightly soft photos
- normal everyday snapshots

Suggested go/no-go threshold:

- at least about 2/3 of images should be clearly preferred after enhancement
- very few images should become obviously worse
- no strange faces, fake details or extreme colors
- processing should remain local

## Current implementation

This first implementation uses deterministic bitmap processing rather than generative restoration. It adapts exposure, shadow lift, highlight compression, vibrance and sharpening from image statistics.

The next step is empirical tuning using real photos. Do not add product features until the enhancement quality is proven.
