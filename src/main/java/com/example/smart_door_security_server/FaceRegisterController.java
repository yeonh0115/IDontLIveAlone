package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/face")
@RequiredArgsConstructor
public class FaceRegisterController {

    private final FaceInfoRepository faceInfoRepository;
    private final TaskQueueService taskQueueService;
    
    // 절대 경로를 안전하게 확보하기 위해 프로젝트 루트 디렉터리를 기준으로 잡습니다.
    private final String UPLOAD_DIR = System.getProperty("user.dir") + "/pictures/";

    @PostMapping("/register")
    public ResponseEntity<String> registerFace(
            @RequestParam("userId") String userId,
            @RequestParam("file1") MultipartFile file1,
            @RequestParam("file2") MultipartFile file2,
            @RequestParam("file3") MultipartFile file3) {

        try {
            // OS 상관없이 절대 경로로 디렉터리 생성
            Path userPath = Paths.get(UPLOAD_DIR, userId).toAbsolutePath().normalize();
            if (!Files.exists(userPath)) {
                Files.createDirectories(userPath);
            }

            String timestamp = String.valueOf(System.currentTimeMillis());
            String fileName1 = "face1_" + timestamp + ".jpg";
            String fileName2 = "face2_" + timestamp + ".jpg";
            String fileName3 = "face3_" + timestamp + ".jpg";

            // 파일 저장 및 DB 저장을 위한 상대 경로(or 절대 경로) 획득
            String path1 = saveFile(file1, userPath, fileName1);
            String path2 = saveFile(file2, userPath, fileName2);
            String path3 = saveFile(file3, userPath, fileName3);

            // DB 저장 로직
            FaceInfo faceInfo = faceInfoRepository.findByUserId(userId).orElse(new FaceInfo());
            faceInfo.setUserId(userId);
            faceInfo.setFilePath1(path1);
            faceInfo.setFilePath2(path2);
            faceInfo.setFilePath3(path3);
            faceInfo.setUpdatedAt(LocalDateTime.now());
            faceInfoRepository.save(faceInfo);

            // 웹에서 다운로드할 수 있는 URL 생성
            String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
            
            String url1 = baseUrl + "/pictures/" + userId + "/" + fileName1;
            String url2 = baseUrl + "/pictures/" + userId + "/" + fileName2;
            String url3 = baseUrl + "/pictures/" + userId + "/" + fileName3;
            List<String> imageUrls = Arrays.asList(url1, url2, url3);

            // 큐에 작업 등록
            taskQueueService.addTrainTask(userId, imageUrls);

            return ResponseEntity.ok("success");

        } catch (IOException e) {
            // 500 에러 발생 시 로그에서 원인을 쉽게 찾을 수 있도록 서버 콘솔에도 에러 StackTrace를 출력합니다.
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("fail: " + e.getMessage());
        }
    }

    private String saveFile(MultipartFile file, Path userPath, String fileName) throws IOException {
        if (file.isEmpty()) return "";
        
        // 대상 파일의 절대 경로 지정
        Path filePath = userPath.resolve(fileName).toAbsolutePath().normalize();
        
        // 🌟 핵심: toFile() 호출 시 절대 경로를 갖는 File 객체를 전달하여 임시 디렉터리 혼선을 방지
        file.transferTo(filePath.toFile());
        
        return filePath.toString(); 
    }
}
