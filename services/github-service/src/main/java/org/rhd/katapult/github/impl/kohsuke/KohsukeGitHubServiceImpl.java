package org.rhd.katapult.github.impl.kohsuke;

import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.rhd.katapult.github.api.GitHubRepository;
import org.rhd.katapult.github.api.GitHubService;
import org.rhd.katapult.github.api.NoSuchRepositoryException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of {@link GitHubService} backed by the Kohsuke GitHub Java Client
 * http://github-api.kohsuke.org/
 *
 * @author <a href="mailto:alr@redhat.com">Andrew Lee Rubinger</a>
 */
final class KohsukeGitHubServiceImpl implements GitHubService {

    private static final Logger log = Logger.getLogger(KohsukeGitHubServiceImpl.class.getName());

    private static final String MSG_NOT_FOUND = "Not Found";

    private final GitHub delegate;

    /**
     * Creates a new instance with the specified, required delegate
     *
     * @param delegate
     */
    KohsukeGitHubServiceImpl(final GitHub delegate) {
        assert delegate != null : "delegate must be specified";
        this.delegate = delegate;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GitHubRepository fork(final String repositoryFullName) throws NoSuchRepositoryException,
            IllegalArgumentException {
        // Precondition checks
        if (repositoryFullName == null || repositoryFullName.isEmpty()) {
            throw new IllegalArgumentException("repository name must be specified");
        }

        // First get the source repo
        final GHRepository source, target;
        try {
            source = delegate.getRepository(repositoryFullName);
        } catch (final IOException ioe) {
            // Check for repo not found (this is how Kohsuke Java Client reports the error)
            if (isRepoNotFound(ioe)) {
                throw new NoSuchRepositoryException("Could not fork specified repository "
                        + repositoryFullName + " because it could not be found.");
            }
            throw new RuntimeException("Could not fork " + repositoryFullName, ioe);
        }

        // Fork
        try {
            target = source.fork();
        } catch (final IOException ioe) {
            throw new RuntimeException("Could not fork requested repository " + repositoryFullName, ioe);
        }

        // Block on the full creation of the new fork, which is an async op
        GHRepository newlyCreatedRepo = null;
        final String targetRepoFullName = target.getFullName();
        for (int i = 0; i < 10; i++) {
            try {
                newlyCreatedRepo = delegate.getRepository(targetRepoFullName);
            } catch (final IOException ioe) {
                // Throw an exception if this error is anything other than not found repo
                if (!this.isRepoNotFound(ioe)) {
                    throw new RuntimeException("Error in not find newly-created repo " + targetRepoFullName, ioe);
                }
            }

            // Still no repo?  Sleep a bit and try again
            if (newlyCreatedRepo == null) {
                try {
                    Thread.sleep(3000);
                } catch (final InterruptedException ie) {
                    Thread.interrupted();
                    throw new RuntimeException(ie);
                }
            }
        }
        // Still can't find it after a few tries and waiting?  Fail.
        if (newlyCreatedRepo == null) {
            throw new RuntimeException(repositoryFullName + " was forked into " + targetRepoFullName +
                    " but can't find the new repository");
        }

        // Wrap in our API view and return
        final GitHubRepository wrapped = new KohsukeGitHubRepositoryImpl(newlyCreatedRepo);
        if (log.isLoggable(Level.FINEST)) {
            log.log(Level.FINEST, "Forked " + source.getFullName() + " as " + newlyCreatedRepo.getFullName() +
                    " available at " + newlyCreatedRepo.getGitTransportUrl());
        }
        return wrapped;
    }

    /**
     * Determines if the required {@link IOException} in question represents a repo
     * that can't be found
     *
     * @param ioe
     * @return
     */
    private boolean isRepoNotFound(final IOException ioe) {
        assert ioe != null : "ioe is required";
        return (ioe.getClass() == FileNotFoundException.class &&
                ioe.getMessage().contains(MSG_NOT_FOUND));
    }
}
