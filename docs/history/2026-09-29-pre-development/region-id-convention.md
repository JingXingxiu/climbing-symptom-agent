# Coarse hand region ID convention

`regionId` is a stable business vocabulary for coarse expression. It must not be a mesh name, triangle index, vertex index or translated display string.

Format:

```text
hand.{left|right}.{structure}.{segment}
```

Initial examples:

| Meaning | Example ID |
| --- | --- |
| Right middle finger, PIP area | `hand.right.middle.pip` |
| Left index finger, DIP area | `hand.left.index.dip` |
| Right thumb, IP area | `hand.right.thumb.ip` |
| Left little finger, MCP area | `hand.left.little.mcp` |
| Right central palm | `hand.right.palm.center` |
| Left thenar area | `hand.left.palm.thenar` |
| Right wrist area | `hand.right.wrist.center` |

Allowed finger structures are initially `thumb`, `index`, `middle`, `ring`, and `little`. Initial segment names are `mcp`, `pip`, `dip`, `ip`, `proximal`, `middle`, `distal`, `palm.center`, `palm.thenar`, `palm.hypothenar`, and `wrist.center`. The frontend may expose a smaller tested subset first.

Rules:

- Store the ID and a human-readable label together; the label can change without changing the ID.
- Side is always explicit, including when the left hand is rendered by mirroring a right-hand GLB.
- Hotspot layout/version belongs to the frontend asset manifest. Changing hotspot geometry does not require changing an ID if its semantic meaning is unchanged.
- New IDs are additive. Do not silently reuse an old ID for a different region.
- A precise snapshot annotation refines the coarse ID; it does not replace it.

