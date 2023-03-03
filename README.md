# git-files-blame
A git command implemented with JGit that blames multiple files simultaneously.

## Usage


```java
BlameResult result = new RepositoryBlameCommand(repo) //JGit repository to be used for the blame
  .setFilePaths(List.of("fileA.java", "fileB.java")) //list of files to blame, all files of repository if not specified 
  .setMultithreading(true)
  .setStartCommit(commitId) //JGit commit id from where the blame will start, HEAD if not specified
  .call();
```

## Our idea

Currently, the git blame command is only applicable to a single file: We specify the file path to be blamed, and git returns the blame result for this single file by traversing the history of commits.  
In the event where we want to blame multiple files, we would need to call this command many times. Even though this operation can be parallelized, it has been proven to be slow on large volume of files: For each file, the original blame algorithm traverse all the commits from newest to oldest until the file is fully blamed.

Our tool propose to blame every file simultaneously, traversing the commit graph only once and saving the cost of operations that are normally done for each file.

## Have Question or Feedback?

For support questions ("How do I?", "I got this error, why?", ...), please first read the [documentation](https://docs.sonarqube.org) and then head to the [SonarSource Community](https://community.sonarsource.com/c/help/sq/10). The answer to your question has likely already been answered! 🤓

Be aware that this forum is a community, so the standard pleasantries ("Hi", "Thanks", ...) are expected. And if you don't get an answer to your thread, you should sit on your hands for at least three days before bumping it. Operators are not standing by. 😄

## Contributing

If you would like to see a new feature, please create a new Community thread: ["Suggest new features"](https://community.sonarsource.com/c/suggestions/features).

Please be aware that we are not actively looking for feature contributions. The truth is that it's extremely difficult for someone outside SonarSource to comply with our roadmap and expectations. Therefore, we typically only accept minor cosmetic changes and typo fixes.

With that in mind, if you would like to submit a code contribution, please create a pull request for this repository. Please explain your motives to contribute this change: what problem you are trying to fix, what improvement you are trying to make.

Make sure that you follow our [code style](https://github.com/SonarSource/sonar-developer-toolset#code-style) and all tests are passing (Travis build is executed for each pull request).

Willing to contribute to SonarSource products? We are looking for smart, passionate, and skilled people to help us build world-class code quality solutions. Have a look at our current [job offers here](https://www.sonarsource.com/company/jobs/)!

### Build and Run Unit Tests

Execute from project base directory:

    ./gradlew build
