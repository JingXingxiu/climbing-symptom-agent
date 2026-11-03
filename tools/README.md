# Hand asset build

Runtime assets are checked in under `frontend/src/static/hand`; normal frontend
development does **not** require Python or the 80.5 MB source mesh.

The source is Blender Studio's CC0 hand mirrored on Wikimedia Commons:

- page: <https://commons.wikimedia.org/wiki/File:Hand_realistic_by_Dan_Ulrich_(CC0).stl>
- original file SHA-256: `BBAF01FA5A5C6D12255A5362A52A7E406D9E521166BDEA8043B79306FD9BC7F7`
- license: CC0 1.0 Universal

To rebuild intentionally:

```powershell
python -m pip install -r tools/requirements-hand-assets.txt
python tools/process_hand_asset.py path/to/source.stl frontend/src/static/hand
```

The script merges the STL mesh, reduces it to about 24,000 faces, normalizes
its axes/scale, writes a GLB, and renders three orthographic reference PNGs.
The business hotspot vocabulary is code in `Hand3DViewer.vue`; it is not
derived from triangle indices.

Expected output SHA-256 values for this delivery:

| Asset | SHA-256 |
| --- | --- |
| `hand-lowpoly.glb` | `178B88B34C71791B25BCA47582FD3E90D8CCB1BD6538FCC8E30B43BBE5214927` |
| `hand-palm.png` | `56F1DF637115830087F31C0F94C47370A16C767DA79CC0F3204F8E12F0AB5443` |
| `hand-dorsal.png` | `29B1533EB8403E4060847D8C07ED2F7A351D5D3793D0BD9FDEF6E4D600F6E01E` |
| `hand-side.png` | `969CD8886937AA4B6B8877EBAE23F3A2B8D3E5293BC96446D47302B340F4CC16` |

Small byte differences can occur after changing dependency versions; visually
inspect all three reference pictures and repeat the mini-program device checks
before accepting a regenerated asset.
