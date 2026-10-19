package com.kopite.devspace.github.application;
import com.kopite.devspace.github.application.port.*;
import com.kopite.devspace.github.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import lombok.RequiredArgsConstructor;
import java.time.Clock;
import java.util.UUID;

@Service @RequiredArgsConstructor @Transactional(propagation=Propagation.NEVER)
public class GitHubDetachService {
    private final GitHubDetachStore store;private final GitHubAuthorizationService authorization;
    private final GitHubRepositoryClient repositories;private final GitHubAccessService access;private final Clock projectClock;
    public UUID request(UUID user,UUID project,UUID link,long revision,String disposition){return store.request(user,project,link,revision,disposition,projectClock.instant());}
    public void convert(UUID user,UUID task,long revision){var link=store.taskLink(user,task);access.refresh(user,link);store.convert(user,task,revision,projectClock.instant());}
    public void runOnce(){
        var candidate=store.next(projectClock.instant());if(candidate.isEmpty())return;var work=candidate.get();
        try{
            GitHubDetachStore.Proof proof=null;
            if(work.disposition().equals("convert")){
                long epoch=access.installationVersion(work.installation());var verified=authorization.authorized(work.user(),token->repositories.verify(token,work.installation(),work.repository()));
                proof=new GitHubDetachStore.Proof(verified.version(),epoch,projectClock.instant().plusSeconds(60));
            }
            store.apply(work,proof,projectClock.instant());
        }catch(GitHubException ex){store.stop(work,ex.code(),projectClock.instant());}
        catch(RuntimeException ex){store.stop(work,"GITHUB_DETACH_FAILED",projectClock.instant());}
    }
}
