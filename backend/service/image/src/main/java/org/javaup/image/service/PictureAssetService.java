package org.javaup.image.service;

import com.baomidou.mybatisplus.spring.service.IService;
import java.util.List;
import java.util.UUID;
import org.javaup.image.api.dto.AnnotationSetResponse;
import org.javaup.image.api.dto.HandSide;
import org.javaup.image.api.dto.PictureResponse;
import org.javaup.image.api.dto.PictureUploadMetadata;
import org.javaup.image.api.dto.ReplaceAnnotationSetRequest;
import org.javaup.image.api.dto.ResolveVisualReferencesRequest;
import org.javaup.image.api.dto.ResolvedVisualReference;
import org.javaup.image.api.dto.StandardPictureView;
import org.javaup.image.entity.PictureAsset;
import org.springframework.web.multipart.MultipartFile;

public interface PictureAssetService extends IService<PictureAsset> {

    PictureResponse uploadPicture(
            UUID currentUserId,
            UUID idempotencyKey,
            MultipartFile file,
            PictureUploadMetadata metadata
    );

    List<PictureResponse> listStandardPictures(
            HandSide handSide,
            StandardPictureView view
    );

    PictureResponse getPicture(UUID currentUserId, UUID pictureId);

    List<ResolvedVisualReference> resolveVisualReferences(
            UUID currentUserId,
            ResolveVisualReferencesRequest request
    );

    void deletePicture(UUID currentUserId, UUID pictureId);

    AnnotationSetResponse getPictureAnnotationSet(
            UUID currentUserId,
            UUID pictureId,
            UUID assessmentId
    );

    AnnotationSetResponse replacePictureAnnotationSet(
            UUID currentUserId,
            UUID pictureId,
            ReplaceAnnotationSetRequest request
    );
}
