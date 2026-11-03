"""Build the lightweight hand assets used by the mini-program.

The source mesh is Blender Studio's CC0 realistic hand.  This script is kept
in the repository so the checked-in GLB and reference pictures are
reproducible without coupling runtime code to a particular modelling tool.

Example:
    python tools/process_hand_asset.py source.stl frontend/src/static/hand
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import trimesh
from PIL import Image, ImageChops, ImageDraw, ImageFilter


TARGET_FACE_COUNT = 24_000
MODEL_HEIGHT = 4.0


def load_and_prepare(source: Path) -> trimesh.Trimesh:
    mesh = trimesh.load_mesh(source, process=True)
    if not isinstance(mesh, trimesh.Trimesh):
        mesh = trimesh.util.concatenate(tuple(mesh.geometry.values()))

    mesh.remove_unreferenced_vertices()
    if len(mesh.faces) > TARGET_FACE_COUNT:
        mesh = mesh.simplify_quadric_decimation(face_count=TARGET_FACE_COUNT)

    # Source axes: X=width, Y=thickness, Z=wrist-to-fingertip.  Runtime axes:
    # X=width, Y=wrist-to-fingertip, Z=thickness.
    source_vertices = np.asarray(mesh.vertices)
    vertices = source_vertices[:, [0, 2, 1]].copy()
    vertices[:, 1] *= -1
    vertices -= (vertices.min(axis=0) + vertices.max(axis=0)) / 2.0
    vertices *= MODEL_HEIGHT / (vertices[:, 1].max() - vertices[:, 1].min())
    mesh.vertices = vertices

    # The axis swap plus Y inversion preserves winding. Trimesh recomputes
    # face normals lazily after the vertex transform. Avoid `fix_normals()`
    # because that path pulls in SciPy only to count disconnected bodies.
    mesh._cache.clear()
    mesh.visual = trimesh.visual.ColorVisuals(
        mesh=mesh, vertex_colors=np.tile([224, 174, 145, 255], (len(mesh.vertices), 1))
    )
    return mesh


def export_glb(mesh: trimesh.Trimesh, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    scene = trimesh.Scene(mesh)
    output.write_bytes(scene.export(file_type="glb"))


def render_orthographic(
    mesh: trimesh.Trimesh,
    output: Path,
    horizontal_axis: int,
    vertical_axis: int,
    depth_axis: int,
    camera_sign: int,
) -> None:
    width, height = 720, 960
    padding = 64
    vertices = np.asarray(mesh.vertices)
    projected = vertices[:, [horizontal_axis, vertical_axis]].copy()
    projected[:, 1] *= -1

    minimum = projected.min(axis=0)
    extent = np.maximum(projected.max(axis=0) - minimum, 1e-6)
    scale = min((width - padding * 2) / extent[0], (height - padding * 2) / extent[1])
    projected = (projected - minimum) * scale
    projected += np.array(
        [(width - extent[0] * scale) / 2, (height - extent[1] * scale) / 2]
    )

    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image, "RGBA")
    faces = np.asarray(mesh.faces)
    face_depth = vertices[faces, depth_axis].mean(axis=1) * camera_sign
    order = np.argsort(face_depth)
    normals = np.asarray(mesh.face_normals)[:, depth_axis] * camera_sign

    base = np.array([224, 174, 145], dtype=float)
    for face_index in order:
        polygon = projected[faces[face_index]]
        edge_a = polygon[1] - polygon[0]
        edge_b = polygon[2] - polygon[0]
        signed_area_twice = edge_a[0] * edge_b[1] - edge_a[1] * edge_b[0]
        if abs(signed_area_twice) < 0.08:
            continue
        illumination = 0.72 + 0.28 * abs(float(normals[face_index]))
        colour = tuple(np.clip(base * illumination, 0, 255).astype(int)) + (255,)
        draw.polygon([tuple(point) for point in polygon], fill=colour)

    alpha = image.getchannel("A")
    outer = alpha.filter(ImageFilter.MaxFilter(9))
    outline = ImageChops.subtract(outer, alpha)
    outlined = Image.new("RGBA", image.size, (104, 78, 67, 0))
    outlined.putalpha(outline.point(lambda value: min(180, value)))
    outlined.alpha_composite(image)
    output.parent.mkdir(parents=True, exist_ok=True)
    outlined.save(output, optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output_dir", type=Path)
    args = parser.parse_args()

    mesh = load_and_prepare(args.source)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    export_glb(mesh, args.output_dir / "hand-lowpoly.glb")
    render_orthographic(mesh, args.output_dir / "hand-palm.png", 0, 1, 2, 1)
    render_orthographic(mesh, args.output_dir / "hand-dorsal.png", 0, 1, 2, -1)
    render_orthographic(mesh, args.output_dir / "hand-side.png", 2, 1, 0, 1)
    print(
        f"faces={len(mesh.faces)} vertices={len(mesh.vertices)} "
        f"extents={np.round(mesh.extents, 3).tolist()}"
    )


if __name__ == "__main__":
    main()
