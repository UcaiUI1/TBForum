package cn.tbforum.repo;

import cn.tbforum.domain.UploadedFile;
import cn.tbforum.domain.UploadedFile.Purpose;
import cn.tbforum.domain.UploadedFile.Status;
import cn.tbforum.domain.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UploadedFileRepository extends JpaRepository<UploadedFile, Long> {
    Optional<UploadedFile> findByUrl(String url);

    Optional<UploadedFile> findByUrlAndStatus(String url, Status status);

    @Query("select coalesce(sum(f.sizeBytes), 0) from UploadedFile f where f.owner=:owner")
    long totalBytesByOwner(@Param("owner") User owner);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from UploadedFile f where f.url=:url")
    Optional<UploadedFile> findByUrlForUpdate(@Param("url") String url);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from UploadedFile f where f.purpose=:purpose and f.referenceId=:referenceId and f.status=:status")
    List<UploadedFile> findReferencedForUpdate(
            @Param("purpose") Purpose purpose, @Param("referenceId") Long referenceId, @Param("status") Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from UploadedFile f where f.status=:status and f.expiresAt<=:now order by f.expiresAt")
    List<UploadedFile> findExpiredForUpdate(
            @Param("status") Status status, @Param("now") Instant now, Pageable pageable);
}
