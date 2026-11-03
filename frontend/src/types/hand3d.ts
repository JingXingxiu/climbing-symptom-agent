import type { HandSide, SymptomLocation } from './domain'

export interface Hand3DSnapshotPayload {
  pictureUrl: string
  width: number
  height: number
  symptomLocations: SymptomLocation[]
  viewState: import('./domain').ViewState3D
}

/** Contract implemented by the isolated real WebGL mini-program component. */
export interface Hand3DViewerProps {
  handSide: HandSide
  selectedRegionId?: string
  interactionMode: 'NAVIGATE' | 'SELECT' | 'LOCKED'
}

export interface Hand3DViewerEvents {
  regionChange: SymptomLocation
  snapshot: Hand3DSnapshotPayload
  error: { code: string; message: string }
}
