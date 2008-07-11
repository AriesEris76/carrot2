package org.carrot2.text.preprocessing;

import java.util.*;

import org.carrot2.text.linguistic.Stemmer;
import org.carrot2.text.preprocessing.PreprocessingContext.*;
import org.carrot2.text.util.*;
import org.carrot2.util.CharArrayUtils;
import org.carrot2.util.CharSequenceUtils;
import org.carrot2.util.attribute.Bindable;

import com.google.common.collect.Lists;

import bak.pcj.list.IntArrayList;
import bak.pcj.list.IntList;
import bak.pcj.set.*;

/**
 * Applies stemming to words and calculates a number of frequency statistics for stems.
 * <p>
 * This class saves the following results to the {@link PreprocessingContext}:
 * <ul>
 * <li>{@link AllWords#stemIndex}</li>
 * <li>{@link AllStems#image}</li>
 * <li>{@link AllStems#mostFrequentOriginalWordIndex}</li>
 * <li>{@link AllStems#tf}</li>
 * <li>{@link AllStems#tfByDocument}</li>
 * </ul>
 * <p>
 * This class requires that {@link Tokenizer} and {@link CaseNormalizer} be invoked first.
 */
@Bindable
public final class LanguageModelStemmer
{
    /**
     * Performs stemming and saves the results to the <code>context</code>.
     */
    public void stem(PreprocessingContext context)
    {
        final MutableCharArray current = new MutableCharArray("");
        final Stemmer stemmer = context.language.getStemmer();

        final char [][] wordImages = context.allWords.image;
        final char [][] stemImages = new char [wordImages.length] [];

        for (int i = 0; i < wordImages.length; i++)
        {
            final char [] lowerCaseWord = CharArrayUtils.toLowerCase(wordImages[i]);
            current.reset(lowerCaseWord);
            final CharSequence stemmed = stemmer.stem(current);

            if (stemmed != null)
            {
                stemImages[i] = CharSequenceUtils.toCharArray(stemmed);
            }
            else
            {
                // We need to put the original word here, otherwise, we wouldn't be able
                // to compute frequencies for stems.
                stemImages[i] = lowerCaseWord;
            }
        }

        addStemStatistics(context, stemImages);
    }

    /**
     * Adds frequency statistics to the stems.
     */
    private void addStemStatistics(PreprocessingContext context, char [][] wordStemImages)
    {
        final int [] stemImagesOrder = IndirectSorter.sort(wordStemImages,
            CharArrayComparators.FAST_CHAR_ARRAY_COMPARATOR);

        // Local array references
        final int [] wordTfArray = context.allWords.tf;
        final int [][] wordTfByDocumentArray = context.allWords.tfByDocument;
        final byte [][] wordsFieldIndices = context.allWords.fieldIndices;

        final int allWordsCount = wordTfArray.length;

        // Pointers from AllWords to AllStems
        final int [] stemIndexesArray = new int [allWordsCount];

        if (stemImagesOrder.length == 0)
        {
            context.allStems.image = new char [0] [];
            context.allStems.mostFrequentOriginalWordIndex = new int [0];
            context.allStems.tf = new int [0];
            context.allStems.tfByDocument = new int [0] [];
            context.allStems.fieldIndices = new byte [0] [];

            context.allWords.stemIndex = new int [context.allWords.image.length];

            return;
        }

        // Lists to accommodate the results
        final List<char []> stemImages = new ArrayList<char []>(allWordsCount);
        final IntList stemTf = new IntArrayList(allWordsCount);
        final IntList stemMostFrequentWordIndexes = new IntArrayList(allWordsCount);
        final List<int []> stemTfByDocumentList = new ArrayList<int []>(allWordsCount);
        final List<byte []> fieldIndexList = Lists.newArrayList();

        // Counters
        int totalTf = wordTfArray[stemImagesOrder[0]];
        int mostFrequentWordFrequency = wordTfArray[stemImagesOrder[0]];
        int mostFrequentWordIndex = stemImagesOrder[0];
        final IntSet originalWordIndexesSet = new IntBitSet(allWordsCount);
        originalWordIndexesSet.add(stemImagesOrder[0]);
        int stemIndex = 0;
        final int [] stemTfByDocument = new int [context.documents.size()];
        IntArrayUtils.addAllFromSparselyEncoded(stemTfByDocument,
            wordTfByDocumentArray[stemImagesOrder[0]]);
        final ByteBitSet fieldIndices = new ByteBitSet(
            (byte) context.allFields.name.length);
        addAll(fieldIndices, wordsFieldIndices[0]);

        // Go through all words in the order of stem images
        for (int i = 0; i < stemImagesOrder.length - 1; i++)
        {
            final char [] stem = wordStemImages[stemImagesOrder[i]];
            final int nextInOrderIndex = stemImagesOrder[i + 1];
            final char [] nextStem = wordStemImages[nextInOrderIndex];

            stemIndexesArray[stemImagesOrder[i]] = stemIndex;

            // Now check if token image is changing
            final boolean sameStem = CharArrayComparators.FAST_CHAR_ARRAY_COMPARATOR
                .compare(stem, nextStem) == 0;

            if (sameStem)
            {
                totalTf += wordTfArray[nextInOrderIndex];
                IntArrayUtils.addAllFromSparselyEncoded(stemTfByDocument,
                    wordTfByDocumentArray[nextInOrderIndex]);
                addAll(fieldIndices, wordsFieldIndices[nextInOrderIndex]);
                if (mostFrequentWordFrequency < wordTfArray[nextInOrderIndex])
                {
                    mostFrequentWordFrequency = wordTfArray[nextInOrderIndex];
                    mostFrequentWordIndex = nextInOrderIndex;
                }
                originalWordIndexesSet.add(nextInOrderIndex);
            }
            else
            {
                stemImages.add(stem);
                stemTf.add(totalTf);
                stemMostFrequentWordIndexes.add(mostFrequentWordIndex);
                stemTfByDocumentList
                    .add(IntArrayUtils.toSparseEncoding(stemTfByDocument));
                fieldIndexList.add(fieldIndices.toArray());

                stemIndex++;
                totalTf = wordTfArray[nextInOrderIndex];
                mostFrequentWordFrequency = wordTfArray[nextInOrderIndex];
                mostFrequentWordIndex = nextInOrderIndex;
                originalWordIndexesSet.clear();
                originalWordIndexesSet.add(nextInOrderIndex);
                fieldIndices.clear();
                addAll(fieldIndices, wordsFieldIndices[nextInOrderIndex]);

                Arrays.fill(stemTfByDocument, 0);
                IntArrayUtils.addAllFromSparselyEncoded(stemTfByDocument,
                    wordTfByDocumentArray[nextInOrderIndex]);
            }
        }

        // Store tf for the last stem in the array
        stemImages.add(wordStemImages[stemImagesOrder[stemImagesOrder.length - 1]]);
        stemTf.add(totalTf);
        stemMostFrequentWordIndexes.add(mostFrequentWordIndex);
        stemIndexesArray[stemImagesOrder[stemImagesOrder.length - 1]] = stemIndex;
        stemTfByDocumentList.add(IntArrayUtils.toSparseEncoding(stemTfByDocument));
        fieldIndexList.add(fieldIndices.toArray());

        // Convert lists to arrays and store them in allStems
        context.allStems.image = stemImages.toArray(new char [stemImages.size()] []);
        context.allStems.mostFrequentOriginalWordIndex = stemMostFrequentWordIndexes
            .toArray();
        context.allStems.tf = stemTf.toArray();
        context.allStems.tfByDocument = stemTfByDocumentList
            .toArray(new int [stemTfByDocumentList.size()] []);
        context.allStems.fieldIndices = fieldIndexList.toArray(new byte [fieldIndexList
            .size()] []);

        // References in allWords
        context.allWords.stemIndex = stemIndexesArray;
    }

    private final static void addAll(ByteBitSet set, byte [] values)
    {
        for (byte b : values)
        {
            set.add(b);
        }
    }
}
