package ni.edu.uam.campusfood.assessment;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/attempts/{attemptId}/material")
public class AssessmentMaterialController {
    private final AssessmentService service;

    public AssessmentMaterialController(AssessmentService service) {
        this.service = service;
    }

    @GetMapping(value = "/{itemIndex}/{asset}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<Resource> material(Authentication authentication,
                                             @PathVariable Long attemptId,
                                             @PathVariable int itemIndex,
                                             @PathVariable String asset) {
        service.authorizeMaterial(Long.valueOf(authentication.getName()), attemptId, itemIndex);
        if (!asset.matches("base|p[1-8]")) {
            throw new IllegalArgumentException("Recurso de prueba no válido.");
        }
        int section = itemIndex / 12 + 1;
        int local = itemIndex % 12 + 1;
        String path = "test-material/weil-form-a/puzzle/l%di%02d-%s.png"
                .formatted(section, local, asset);
        Resource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Recurso de prueba no encontrado.");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG)
                .body(resource);
    }
}
